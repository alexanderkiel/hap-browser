(ns hap-browser.coerce
  (:require [schema.coerce :as c]))

(defn map-matcher [schema]
  (when (and (not (record? schema)) (map? schema))
    (c/safe c/edn-read-string)))

(defn coercion-matcher [schema]
  (or (c/+string-coercions+ schema)
      (c/keyword-enum-matcher schema)
      (map-matcher schema)))
