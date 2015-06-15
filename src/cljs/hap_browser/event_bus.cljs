(ns hap-browser.event-bus
  "Global Events:

  TODO: copy of lens.event-bus"
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as async :refer [<!]]
            [om.core :as om]))

(defn publisher [owner]
  (:publisher (om/get-shared owner :event-bus)))

(defn publication [owner]
  (:publication (om/get-shared owner :event-bus)))

(defn init-bus []
  (let [publisher (async/chan)]
    {:publisher publisher
     :publication (async/pub publisher #(:topic %))}))

(defn register-for-unlisten [owner topic ch]
  (om/update-state! owner ::subs #(conj % [topic ch])))

(defn listen-on
  "Listens on a topic of the publication. Calls the callback with the message."
  [owner topic callback]
  (let [ch (async/chan)]
    (register-for-unlisten owner topic ch)
    (async/sub (publication owner) topic ch)
    (go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (callback msg)
        (recur)))))

(defn listen-on-mult
  "Listens on multiple topics of the publication.

  Callbacks are reduce functions over one single immediate result starting with
  start. Callbacks are supplied in a map from topic to callback.

  Example:

  Listens on :topic-a and :topic-b. Does something with messages of :topic-a
  incrementing a counter every time. Resets the counter on every occurence of
  arbitary messages of :topic-b.

  (listen-on-mult owner
    {:topic-a
     (fn [count msg]
       (do-something! msg)
       (inc count))
     :topic-b
     (fn [_ _]
       0)}
    0)"
  [owner topic-callback-map start]
  (let [ch (async/chan)]
    (doseq [[topic] topic-callback-map]
      (register-for-unlisten owner topic ch)
      (async/sub (publication owner) topic ch))
    (go-loop [result start]
      (when-let [{:keys [topic msg]} (<! ch)]
        (recur ((topic-callback-map topic) result msg))))))

(defn unlisten-all [owner]
  (doseq [[topic ch] (om/get-state owner ::subs)]
    (async/unsub (publication owner) topic ch)
    (async/close! ch)))

(defn publish! [owner topic msg]
  (async/put! (publisher owner) {:topic topic :msg msg}))
