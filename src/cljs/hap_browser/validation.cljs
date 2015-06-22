(ns hap-browser.validation
  (:require [schema.core :as s]
            [clojure.walk :refer [postwalk]]))

(defn eval-type-constructor [[constructor & args]]
  (condp = constructor
    'enum (apply s/enum args)
    (cons constructor args)))

(defn eval-schema-var [sym]
  (condp = sym
    'Str (s/pred string? 'string?)
    'Int s/Int
    sym))

(defn eval-schema [t]
  (-> (fn [form]
        (cond
          (symbol? form) (eval-schema-var form)
          (seq? form) (eval-type-constructor form)
          :else form))
      (postwalk t)))
