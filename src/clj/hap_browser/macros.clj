(ns hap-browser.macros
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro h
  "Handler for :on-click and others. Calls .preventDefault on event."
  [& body]
  `(fn [e#] ~@body (.preventDefault e#)))
