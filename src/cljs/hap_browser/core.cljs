(ns hap-browser.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [plumbing.core :refer [fnk defnk letk when-letk for-map]]
                   [hap-browser.macros :refer [h <?]])
  (:require [plumbing.core :refer [assoc-when map-vals conj-when]]
            [clojure.string :as str]
            [cljs.core.async :refer [put! chan <!]]
            [goog.dom :as dom]
            [goog.events :as events]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as d :include-macros true]
            [hap-client.core :as hap]
            [hap-browser.event-bus :as bus]
            [hap-browser.util :as util]
            [hap-browser.alert :as alert :refer [alert!]]))

(enable-console-print!)

(defonce app-state
  (atom
    {:alert {}
     :fetch-bar {}}))

(defonce figwheel-reload-ch
  (let [ch (chan)]
    (events/listen (.-body js/document) "figwheel.js-reload" #(put! ch %))
    ch))

(defn figwheel-reload-loop [owner]
  (go-loop []
    (when (<! figwheel-reload-ch)
      (om/refresh! owner)
      (recur))))

(defn convert-queries [queries]
  (mapv (fn [[id query]] [id (update query :params #(into [] %))]) queries))

(defn convert-doc
  "Convert all forms like {:id-1 {} :id-2 {}} into [[:id-1 {}] [:id-2 {}]]
  because we need lists of things instead of maps for om."
  [doc]
  (-> doc
      (update :links (partial into []))
      (update :queries convert-queries)
      (update :forms convert-queries)
      (update :embedded (partial into []))))

(defn set-uri-and-doc! [app-state uri doc]
  (om/transact! app-state #(-> (assoc-in % [:fetch-bar :uri] uri)
                               (assoc :doc (convert-doc doc)))))

(defn fetch-loop
  "Listens on the :fetch topic. Tries to fetch the resource."
  [app-state owner]
  (bus/listen-on owner :fetch
    (fn [resource]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (set-uri-and-doc! app-state (str resource) (<? (hap/fetch resource)))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger e))
              (alert! owner :danger e))))))))

(defn execute-query [query]
  (hap/execute query (map-vals :value (:params query))))

(defn execute-query-loop
  "Listens on the :execute-query topic. Tries to execute the query."
  [app-state owner]
  (bus/listen-on owner :execute-query
    (fn [query]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (set-uri-and-doc! app-state nil (<? (execute-query query)))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger e))
              (alert! owner :danger e))))))))

(defn to-args [params]
  (for-map [[id {:keys [value]}] params
            :when value]
    id value))

(defn create-and-fetch [form]
  (go
    (try
      (<? (hap/fetch (<? (hap/create form (to-args (:params form))))))
      (catch js/Error e e))))

(defn create-loop
  "Listens on the :create topic. Tries to create the resource."
  [app-state owner]
  (bus/listen-on owner :create
    (fn [form]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (let [doc (<? (create-and-fetch form))]
            (set-uri-and-doc! app-state (str (:self (:links doc))) doc))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger e))
              (alert! owner :danger (str "Unexpected error: " e)))))))))

(defn delete-loop
  "Listens on the :delete topic. Tries to delete the resource."
  [app-state owner]
  (bus/listen-on owner :delete
    (fn [{:keys [resource up]}]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (<? (hap/delete resource))
          (alert! owner :success (str "Successfully deleted " resource "."))
          (when up
            (set-uri-and-doc! app-state (str up) (<? (hap/fetch up))))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger e))
              (alert! owner :danger (str "Unexpected error: " e)))))))))

;; ---- Fetch Bar -------------------------------------------------------------

(defcomponent fetch-bar [fetch-bar owner]
  (render [_]
    (d/form {:class "form-inline" :style {:margin-bottom "20px"}}
      (d/div {:class "form-group"}
        (d/label {:class "sr-only" :for "input-uri"} "URI")
        (d/input {:class "form-control" :id "input-uri" :type "text"
                  :style {:width "400px"
                          :margin-right "10px"}
                  :placeholder "http://..."
                  :value (:uri fetch-bar)
                  :on-change #(om/update! fetch-bar :uri (util/target-value %))}))
      (d/button {:class "btn btn-default" :type "submit"
                 :on-click (h (bus/publish! owner :fetch (hap/resource (:uri fetch-bar))))}
                "Fetch"))))

;; ---- Data ------------------------------------------------------------------

(defcomponent data-row [[k v] _]
  (render [_]
    (d/tr
      (d/td (str k))
      (d/td (str v)))))

(defcomponent data-table [data _]
  (render [_]
    (d/table {:class "table table-bordered table-hover"}
      (d/thead (d/tr (d/th "key") (d/th "value")))
      (apply d/tbody (om/build-all data-row data)))))

;; ---- Links -----------------------------------------------------------------

(defcomponent link-row [[rel resource] owner]
  (render [_]
    (d/tr
      (d/td (str rel))
      (d/td
        (d/a {:href "#"
              :on-click (h (bus/publish! owner :fetch resource))}
          (str resource))))))

(defcomponent link-table [links _]
  (render [_]
    (d/table {:class "table table-bordered table-hover"}
      (d/thead (d/tr (d/th "rel") (d/th "resource")))
      (apply d/tbody (om/build-all link-row links)))))

;; ---- Embedded -----------------------------------------------------------------

(defcomponent embedded-row [rep owner {:keys [rel]}]
  (render [_]
    (d/tr
      (d/td (str rel))
      (d/td
        (if-let [res (:self (:links rep))]
          (d/a {:href "#" :on-click (h (bus/publish! owner :fetch res))} (str res))
          "<empty>")))))

(defcomponent embedded-row-group [[rel reps] _]
  (render [_]
    (apply d/tbody (om/build-all embedded-row (or (seq reps) [nil]) {:opts {:rel rel}}))))

(defcomponent embedded-table [embedded _]
  (render [_]
    (apply d/table {:class "table table-bordered table-hover"}
           (d/thead (d/tr (d/th "rel") (d/th "resource")))
           (om/build-all embedded-row-group embedded))))

;; ---- Query -----------------------------------------------------------------

(defn kw->id [kw]
  (if-let [ns (namespace kw)]
    (str ns "_" (name kw))))

(defn form-control-id [query-key key]
  (str (kw->id query-key) "_" (kw->id key)))

(defn upper-first-char [s]
  (->> (str/split s #"[-.]")
       (map #(apply str (str/upper-case (str (first %))) (rest %)))
       (str/join " ")))

(defn kw->label [key]
  (if-let [ns (namespace key)]
    (str (upper-first-char ns) " " (upper-first-char (name key)))
    (upper-first-char (name key))))

(defn build-class [init x & xs]
  (str/join " " (apply conj-when [init] x xs)))

(defn value-updater [param]
  #(om/update! param :value (util/target-value %)))

(defcomponent query-group [[key param] _ {:keys [query-key]}]
  (render [_]
    (d/div {:class (build-class "form-group" (when-not (:optional param) "required"))}
      (d/label {:for (form-control-id query-key key)} (kw->label key))
      (condp = (:type param)
        (d/input {:class "form-control"
                  :id (form-control-id query-key key)
                  :type "text"
                  :value (:value param)
                  :placeholder (str (:type param))
                  :on-change (value-updater param)}))
      (when-let [desc (:desc param)]
        (d/span {:class "help-block"} desc)))))

(defn- build-query-groups [key query]
  (om/build-all query-group (:params query) {:opts {:query-key key}}))

(defcomponent query [[key query] owner {:keys [topic]}]
  (render [_]
    (d/div {:style {:margin-bottom "10px"}}
      (d/h4 (or (:title query) (kw->label key)))
      (d/form
        (apply d/div (build-query-groups key query))
        (d/button {:class "btn btn-primary" :type "submit"
                   :on-click (h (bus/publish! owner topic query))}
                  "Submit")))))

(defcomponent query-list [queries _ opts]
  (render [_]
    (apply d/div (om/build-all query queries {:opts opts}))))

;; ---- Rep -------------------------------------------------------------------

(defn del-msg [doc]
  {:resource (second (first (filter #(= :self (first %)) (:links doc))))
   :up (second (first (filter #(= :up (first %)) (:links doc))))})

(defcomponent rep [doc owner]
  (render [_]
    (d/div
      (d/h3 "Data")
      (if-let [data (seq (dissoc doc :links :queries :forms :embedded :actions))]
        (om/build data-table data)
        (d/div {:class "border"}
          (d/p "No additional data available.")))

      (d/h3 "Links")
      (if (seq (:links doc))
        (om/build link-table (:links doc))
        (d/div {:class "border"}
          (d/p "No queries available.")))

      (d/h3 "Embedded")
      (if (seq (:embedded doc))
        (om/build embedded-table (:embedded doc))
        (d/div {:class "border"}
          (d/p "No embedded representations available.")))

      (d/h3 "Queries")
      (d/div {:class "border"}
        (if (seq (:queries doc))
          (om/build query-list (:queries doc) {:opts {:topic :execute-query}})
          (d/p "No queries available.")))

      (d/h3 "Forms")
      (d/div {:class "border"}
        (if (seq (:forms doc))
          (om/build query-list (:forms doc) {:opts {:topic :create}})
          (d/p "No forms available.")))

      (d/h3 "Actions")
      (d/div {:class "border"}
        (d/p
          (when (some #{:delete} (:actions doc))
            (d/button {:class "btn btn-primary"
                       :type "button"
                       :on-click (h (bus/publish! owner :delete (del-msg doc)))}
                      "Delete"))
          (when-not (some #{:delete} (:actions doc))
            "No actions available."))))))

;; ---- App -------------------------------------------------------------------

(defcomponent app [app-state owner]
  (will-mount [_]
    (fetch-loop app-state owner)
    (execute-query-loop app-state owner)
    (create-loop app-state owner)
    (delete-loop app-state owner)
    (figwheel-reload-loop owner))
  (will-unmount [_])
  (render [_]
    (d/div {:class "container"}
      (d/div {:class "row"}
        (d/div {:class "col-md-12"}
          (om/build fetch-bar (:fetch-bar app-state))))
      (om/build alert/alert (:alert app-state))
      (when-let [doc (:doc app-state)]
        (om/build rep doc)))))

(om/root app app-state
  {:target (dom/getElement "app")
   :shared {:event-bus (bus/init-bus)}})
