(ns hap-browser.util
  (:require [cljs.core.async :refer [chan put!]]
            [goog.events :as events]
            [om.core :as om]))

;; ---- Events ----------------------------------------------------------------

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type
                   (fn [e] (put! out e)))
    out))

(defn target-value [e]
  (.. e -target -value))

;; ---- Other ----------------------------------------------------------------

(defn scroll-to-top []
  (.scrollTo js/window 0 0))

(def make-vals-sequential
  "Transducer which ensures that map vals are sequential."
  (map (fn [[k v]] [k (if (sequential? v) v [v])])))

(defn deref-cursor [x]
  (if (om/cursor? x) (deref x) x))
