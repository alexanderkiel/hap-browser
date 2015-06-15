(ns hap-browser.util
  (:require [cljs.core.async :refer [chan put!]]
            [goog.events :as events]))

;; ---- Core Async ------------------------------------------------------------

(defn throw-err [e]
  (when (instance? js/Error e) (throw e))
  e)

;; ---- Events ----------------------------------------------------------------

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type
                   (fn [e] (put! out e)))
    out))

(defn target-value [e]
  (.. e -target -value))

;; ---- Events ----------------------------------------------------------------

(defn scroll-to-top []
  (.scrollTo js/window 0 0))
