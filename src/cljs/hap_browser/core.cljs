(ns hap-browser.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [plumbing.core :refer [fnk defnk letk when-letk for-map]]
                   [hap-browser.macros :refer [h]])
  (:require [plumbing.core :refer [assoc-when]]
            [cljs.core.async :as async :refer [put! chan <!]]
            [goog.dom :as dom]
            [goog.events :as events]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as d :include-macros true]
            [hap-browser.event-bus :as bus]))

(enable-console-print!)

(defonce app-state
  (atom
    {}))

(defonce figwheel-reload-ch
  (let [ch (chan)]
    (events/listen (.-body js/document) "figwheel.js-reload" #(put! ch %))
    ch))

(defn figwheel-reload-loop [owner]
  (go-loop []
    (when (<! figwheel-reload-ch)
      (om/refresh! owner)
      (recur))))

(defcomponent app [app-state owner]
  (will-mount [_]
    (figwheel-reload-loop owner))
  (will-unmount [_])
  (render [_]
    (d/div "Hi!")))

(om/root app app-state
  {:target (dom/getElement "app")
   :shared {:event-bus (bus/init-bus)}})
