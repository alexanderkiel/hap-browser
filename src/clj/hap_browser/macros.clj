(ns hap-browser.macros
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro h
  "Handler for :on-click and others. Calls .preventDefault on event."
  [& body]
  `(fn [e#] ~@body (.preventDefault e#)))

(defmacro <? [ch]
  `(~'hap-browser.util/throw-err (~'<! ~ch)))

(defmacro try-go [& body]
  `(go
     (try
       ~@body
       (catch js/Error e# e#))))