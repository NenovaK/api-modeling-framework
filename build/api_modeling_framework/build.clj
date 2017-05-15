(ns api-modeling-framework.build
  (:require [clojure.java.shell :as jsh]
            [clojure.string :as string]
            [cheshire.core :as json]))

;; Project information
(defn project-info [] (-> "project.clj" slurp read-string))
(defn find-project-info [kw] (->> (project-info) (drop-while #(not= % kw)) second))

(def version  (-> (project-info) (nth 2) (string/split #"-") first))
(def project  (-> (project-info) (nth 1) str))
(def description (find-project-info :description))
(def keywords ["raml" "open-api" "swagger" "rdf" "shacl" "api" "modeling"])
(def license  (-> (find-project-info :license) :name))
(def repository "https://github.com/mulesoft-labs/api-modeling-framework")
(def npm-dependencies (->> (find-project-info :npm) :dependencies (map (fn [[n v]] [(str n) (str v)]))(into {})))


;; Packages
(defn npm-package []
  {:name project
   :description description
   :version version
   :main "index"
   :license license
   :repository repository
   :dependencies npm-dependencies})


;; Commands
(defn sh! [& args]
  (println "==> " (string/join " " args))
  (let [{:keys [err out exit]} (apply jsh/sh args)]
    (println out)
    (if (not= exit 0)
      (throw (Exception. err))
      (clojure.string/split-lines out))))

(defn pipe! [args output]
  (println "==> " (string/join " " args) " > " output)
  (let [{:keys [err out exit]} (apply jsh/sh args)]
    (if (not= exit 0)
      (throw (Exception. err))
      (spit output out))))

(defn mkdir [path]
  (sh! "mkdir" "-p" path))

(defn rm [path]
  (sh! "rm" "-rf" path))

(defn cljsbuild [target]
  (sh! "lein" "cljsbuild" "once" target))

(defn no-logging-cljsbuild [target]
  (def env (into {} (System/getenv)))
  (def newEnv (assoc env :TIMBRE_LEVEL ":warn"))
  (println "==> lein cljsbuild once web")
  (jsh/sh "lein" "cljsbuild" "once" target :env newEnv))

(defn clean []
  (sh! "lein" "clean"))

(defn cp [from to]
  (sh! "cp" "-rf" from to))

(defn mv [from to]
  (sh! "mv" from to))

(defn pwd [] (first (sh! "pwd")))

(defn ln [source target]
  (sh! "ln" "-s" (str (pwd) source) (str (pwd) target)))

(defn cd [to]
  (sh! "cd" to))

(defn npm-link [path from]
  (sh! "mkdir" "-p" (str from "/node_modules"))
  (sh! "ln" "-s" (str (pwd) "/" path) (str from "/node_modules/api-modeling-framework")))

(defn npm-install [path]
  (sh! "npm" "--prefix" path "install"))

(defn gulp [path task]
  (sh! "./bindings/js/node_modules/.bin/gulp" "--gulpfile" path task))

(defn local-gulp [from] (str from "/node_modules/.bin/gulp"))

(defn npm-publish-link [package path node_modules_target]
  (let [target-package (str node_modules_target "/" package)]
    (sh! "rm" "-f" target-package)
    (sh! "ln" "-s" path target-package)))

(defn npm-link-package [package]
  (sh! "npm" "link" "package"))

(defn tsc [bin project]
  (sh! bin "-p" project))

(defn local-tsc [from] (str from "/node_modules/.bin/tsc"))

(defn gulp-serve [from]
  (sh! (local-gulp from) "--cwd" from "serve"))

(defmacro no-error [form]
  `(try
     ~form
     (catch Exception ex# nil)))

;; builds

(defn build [target builder]
  (println "* Cleaning output directory")
  (clean)
  (rm "target")
  (rm (str "output/" target))

  (println "* Recreating output directory")
  (mkdir "output")

  (println "* Building " target)
  (builder target)

  (println "* Copying license")
  (cp "LICENSE" (str "output/" target "/LICENSE")))

;; CLI

(defn build-node []
  (println "** Building Target: node\n")
  (build "node" cljsbuild)
  (cp "js" "output/node/js")

  (println "* copy package index file")
  (cp "build/package_files/index.js" "output/node/index.js")

  (println "generating npm package")
  (-> (npm-package)
      (json/generate-string {:pretty true})
      (->> (spit "output/node/package.json")))
  (cp "js" "output/node/js")
  ;; this index file is generated by cljs but is
  ;; not right, paths are wrong and we need some
  ;; other initialisation, we provide our own
  ;; copied from package_files/index.js
  (rm "output/node/js/amf.js"))

(defn compile-js-bindings []
  (npm-install "bindings/js")
  (gulp "./bindings/js/gulpfile.js" "typings")
  (gulp "./bindings/js/gulpfile.js" "compile"))

(defn build-js-bindings-web []
  (println "** Building Target: js-bindings-web\n")
  (build "bindings" cljsbuild)

  (println "* copy package index file")
  (let [deps ["global.cljs = cljs;"
              "global.api_modeling_framework = api_modeling_framework;"]
        data (slurp "output/bindings/amf.js")
        data (reduce (fn [acc l] (str acc "\n" l)) data deps)]
    (spit "output/bindings/index.js" data))

  (println "generating npm package")
  (-> (npm-package)
      (json/generate-string {:pretty true})
      (->> (spit "output/bindings/package.json")))

  ;;(mkdir "bindings/js/node_modules")
  ;;(println "linking generating package")
  ;;(npm-publish-link "api-modeling-framework" "output/bindings" "bindings/js/node_modules")
  ;;
  ;;(println "Building bindings")
  ;;(sh! "npm" "link" "output/node")
  ;;(compile-js-bindings)
  ;;
  ;;(println "Running browserify")
  ;;(pipe! ["browserify" "bindings/js/index.js" "-s" "amf"  "--ignore-missing"] "amf_bindings.js")
  ;;
  ;;
  ;;
  ;;(println "Cleaning bindings output")
  ;;(rm "output/bindings")
  ;;(rm "bindings/js/node_modules")
  ;;
  ;;(println "Copying output")
  ;;(sh! "mv" "amf_bindings.js" "output/amf_bindings.js")
  )


(defn build-js-bindings-node []
  (println "** Building Target: js-bindings-node\n")

  (build-node)

  (println "linking generating package")
  (mkdir "bindings/js/node_modules")
  (npm-publish-link "api-modeling-framework" "output/node" "bindings/js/node_modules")

  (compile-js-bindings)

  (println "Copying output")
  (rm "output/amf-js")
  (rm "bindings/js/node_modules")
  (cp "bindings/js" "output/amf-js"))


(defn build-web []
  (println "** Building Target: web\n")
  (build "web" no-logging-cljsbuild))

(defn -main [& args]
  (try
    (condp = (first args)
      "web"              (build-web)
      "node"             (build-node)
      "js-bindings-web"  (build-js-bindings-web)
      "js-bindings-node" (build-js-bindings-node)
      (do (println "Unknown task")
          (System/exit 2)))
    (catch Exception ex
      (println "Error building project")
      (prn ex)
      (System/exit 1)))
  (System/exit 0))
