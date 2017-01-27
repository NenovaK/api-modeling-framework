(ns raml-framework.parser.model.raml
  (:require [clojure.string :as string]
            [raml-framework.model.document :as document]))

(defn parse-ast-dispatch-function [type node]
  (cond
    (and (nil? type)
         (some? (get node "@location"))
         (some? (get node "@fragment")))      (string/trim (get node "@fragment"))
    (and (nil? type)
         (some? (get node "@location")))      :fragment
    (and (nil? type)
         (nil? (get node "@location"))
         (nil? (get node "@fragment")))       (throw (new #?(:clj Exception :cljs js/Error) (str "Unsupported parsing unit, missing @location or @fragment information")))
    :else                                     nil))

(defmulti parse-ast (fn [node] (parse-ast-dispatch-function node)))

(defmethod parse-ast "#%RAML 1.0" [_ node]
  (let [location (get node "@location")]
    (document/->Document location nil nil "RAML")))

(defmethod parse-ast :fragment [_ node]
  (let [location (get node "@location")]
    (document/->Fragment location nil "RAML")))
