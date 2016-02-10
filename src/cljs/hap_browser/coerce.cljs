(ns hap-browser.coerce
  (:require [schema.coerce :as c]
            [clojure.string :as str]))

(defn map-matcher [schema]
  (when (and (not (record? schema)) (map? schema))
    (c/safe c/edn-read-string)))

(defn vector-matcher [schema]
  (when (vector? schema)
    (c/safe c/edn-read-string)))

(defn set-matcher [schema]
  (when (set? schema)
    (fn [x]
      (when x
        (set (str/split (str/trim x) #"\s*,\s*"))))))

(defn coercion-matcher [schema]
  (or (c/+string-coercions+ schema)
      (c/keyword-enum-matcher schema)
      (map-matcher schema)
      (vector-matcher schema)
      (set-matcher schema)))
