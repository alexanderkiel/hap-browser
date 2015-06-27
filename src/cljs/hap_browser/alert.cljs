(ns hap-browser.alert
  (:require-macros [plumbing.core :refer [fnk]]
                   [hap-browser.macros :refer [h]])
  (:require [om.core :as om]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as d :include-macros true]
            [hap-browser.event-bus :as bus]))

(defn alert! [owner level msg]
  (bus/publish! owner ::open {:level level :msg msg}))

(defn close! [owner]
  (bus/publish! owner ::close {}))

(defn close-button [owner]
  (d/button {:type "button" :class "close" :on-click (h (close! owner))}
            (d/span "\u00D7")))

(defcomponent alert [alert owner]
  (will-mount [_]
    (bus/listen-on owner ::open
      (fnk [level msg]
        (om/transact! alert #(assoc % :level level :msg msg))))
    (bus/listen-on owner ::close #(om/update! alert :level nil)))
  (will-unmount [_]
    (bus/unlisten-all owner))
  (render [_]
    (when-let [level (some-> (:level alert) (name))]
      (d/div {:class (str "alert alert-" level " alert-dismissible")
              :role "alert"}
        (close-button owner)
        (:msg alert)))))
