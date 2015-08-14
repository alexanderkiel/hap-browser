(ns hap-browser.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [plumbing.core :refer [fnk defnk letk when-letk for-map]]
                   [hap-browser.macros :refer [h]])
  (:require [plumbing.core :refer [assoc-when map-vals conj-when]]
            [clojure.string :as str]
            [goog.string :as gs]
            [cljs.core.async :refer [put! chan <!]]
            [async-error.core :refer-macros [go-try <?]]
            [goog.dom :as dom]
            [goog.events :as events]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as d :include-macros true]
            [hap-client.core :as hap]
            [hap-browser.event-bus :as bus]
            [hap-browser.coerce :as bc]
            [hap-browser.util :as util]
            [hap-browser.alert :as alert :refer [alert!]]
            [schema.core :as s]
            [schema.coerce :as c]))

(enable-console-print!)

(defonce app-state
  (atom
    {:alert {}
     :location-bar {}}))

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

(defn raw-value [x]
  (cond
    (string? x) x
    (keyword? x) (name x)
    :else (pr-str x)))

(defn create-data [doc]
  (reduce-kv
    (fn [r k v]
      (let [find-schema (get-in doc [:embedded :profile :data :schema k])]
        (conj r (assoc-when {:key k :value v :raw-value (raw-value v)}
                            :type find-schema))))
    []
    (:data doc)))

(def sequential-vals (map (fn [[rel x]] [rel (if (sequential? x) x [x])])))

(defn convert-doc
  "Convert all forms like {:id-1 {} :id-2 {}} into [[:id-1 {}] [:id-2 {}]]
  because we need lists of things instead of maps for om."
  [doc]
  (-> doc
      (assoc :data-view (create-data doc))
      (assoc :links-view (into [] sequential-vals (:links doc)))
      (update :queries convert-queries)
      (update :forms convert-queries)
      (assoc :embedded-view (into [] sequential-vals (:embedded doc)))))

(defn resolve-profile [doc]
  (go-try
    (if-let [profile-link (-> doc :links :profile)]
      (assoc-in doc [:embedded :profile] (<? (hap/fetch profile-link)))
      doc)))

(defn- to-uri [resource]
  (some-> (or (:href resource) resource) (str)))

(defn set-uri-and-doc! [app-state resource doc]
  (om/transact! app-state #(-> (->> (to-uri resource)
                                    (assoc-in % [:location-bar :uri]))
                               (assoc :doc (convert-doc doc)))))

(defn unexpected-error! [owner e]
  (do (alert! owner :danger (str "Unexpected error: " (.-message e)))
      (println (.-stack e))))

(defn fetch-and-resolve-profile [resource]
  (go-try
    (let [doc (<? (hap/fetch resource))]
      (<? (resolve-profile doc)))))

(defn fetch-loop
  "Listens on the :fetch topic. Tries to fetch the resource."
  [app-state owner]
  (bus/listen-on owner :fetch
    (fn [resource]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (->> (<? (fetch-and-resolve-profile resource))
               (set-uri-and-doc! app-state resource))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str (.-message e)
                                           (when-let [status (:status ex-data)]
                                             (str " Status was " status ".")))))
              (unexpected-error! owner e))))))))

(defn execute-query [query]
  (hap/execute query (map-vals :value (:params query))))

(defn execute-query-loop
  "Listens on the :execute-query topic. Tries to execute the query."
  [app-state owner]
  (bus/listen-on owner :execute-query
    (fn [[_ query]]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (->> (<? (execute-query query))
               (set-uri-and-doc! app-state nil))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str "Fetch error: " (.-message e))))
              (unexpected-error! owner e))))))))

(defn to-args [params]
  (for-map [[id {:keys [value]}] params
            :when value]
    id value))

(defn create-and-fetch [form]
  (go-try
    (let [resource (<? (hap/create form (to-args (:params form))))]
      (<? (fetch-and-resolve-profile resource)))))

(defn assoc-form [doc key form]
  (update doc :forms #(assoc % key form)))

(defn create-loop
  "Listens on the :create topic. Tries to create the resource."
  [app-state owner]
  (bus/listen-on owner :create
    (fn [[key form]]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (let [doc (<? (create-and-fetch form))]
            (set-uri-and-doc! app-state (:self (:links doc)) doc))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil (assoc-form doc key form))
                (alert! owner :danger (str "Create error: " (.-message e))))
              (unexpected-error! owner e))))))))

(defn update-loop
  "Listens on the :update topic."
  [app-state owner]
  (bus/listen-on owner :update
    (fn [doc]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (let [doc (<? (hap/update (-> doc :links :self) doc))]
            (set-uri-and-doc! app-state (-> doc :links :self) doc))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str "Update error: " (.-message e))))
              (unexpected-error! owner e))))))))

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
          (alert! owner :success (str "Successfully deleted " (to-uri resource) "."))
          (when up
            (set-uri-and-doc! app-state up (<? (hap/fetch up))))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str "Delete error: " (.-message e))))
              (unexpected-error! owner e))))))))

;; ---- Location Bar -------------------------------------------------------------

(defn- add-http [s]
  (if (or (gs/startsWith s "http://")
          (gs/startsWith s "https://"))
    s
    (str "http://" s)))

(defcomponent location-bar [bar owner]
  (render [_]
    (d/form {:class "form-inline" :style {:margin-bottom "20px"}}
      (d/div {:class "form-group"}
        (d/label {:class "sr-only" :for "input-uri"} "URI")
        (d/input {:class "form-control" :id "input-uri" :type "text"
                  :style {:width "400px"
                          :margin-right "10px"}
                  :placeholder "http://..."
                  :value (:uri bar)
                  :on-change #(om/update! bar :uri (util/target-value %))}))
      (d/button {:class "btn btn-default" :type "submit"
                 :on-click (h (bus/publish! owner :fetch (add-http (:uri bar))))}
                "Fetch"))))

;; ---- Data ------------------------------------------------------------------

(defn kw->id [kw]
  (if-let [ns (namespace kw)]
    (str ns "_" (name kw))))

(defn form-control-id [query-key key]
  (str (kw->id query-key) "_" (kw->id key)))

(defn build-class
  "Builds a CSS class string out of possible nil components."
  [init x & xs]
  (str/join " " (apply conj-when [init] x xs)))

(defn value-updater [param]
  (fn [x]
    (let [raw-val (util/target-value x)
          raw-val (when-not (str/blank? raw-val) raw-val)
          coercer (c/coercer @(:type param) bc/coercion-matcher)
          val (coercer raw-val)]
      (om/update! param :raw-value raw-val)
      (if (schema.utils/error? val)
        (om/transact! param #(assoc % :error (schema.utils/error-val val)
                                      :value nil))
        (om/transact! param #(assoc % :error nil
                                      :value val))))))

(defcomponent data-row [{:keys [key raw-value type error edit] :as item} _]
  (render [_]
    (d/tr
      (d/td (pr-str key))
      (d/td
        (if (and edit type)
          (d/div {:class (build-class "form-group" (when error "has-error"))}
            (cond
              :else
              (d/input {:class "form-control"
                        :id (form-control-id :update key)
                        :type "text"
                        :value raw-value
                        :placeholder (-> type deref s/explain pr-str)
                        :on-change (value-updater item)}))
            (when-let [error (:error item)]
              (d/span {:class "help-block"} (pr-str error))))
          raw-value)))))

(defcomponent data-table [data _]
  (render [_]
    (d/table {:class "table table-bordered table-hover"}
      (d/thead (d/tr (d/th "key") (d/th "value")))
      (apply d/tbody (om/build-all data-row data)))))

;; ---- Links -----------------------------------------------------------------

(defn render-link [link owner]
  (d/a {:href "#"
        :on-click (h (bus/publish! owner :fetch link))}
    (or (:label link) (str (:href link)))))

(defn render-first-link-row [[rel count link] owner]
  (d/tr
    (d/td {:row-span count} (str rel))
    (d/td (render-link link owner))))

(defn render-link-row [link owner]
  (d/tr (d/td (render-link link owner))))

(defcomponent link-row-group [[rel resources] owner]
  (render [_]
    (apply d/tbody
           (render-first-link-row [rel (count resources) (first resources)] owner)
           (map #(render-link-row % owner) (rest resources)))))

(defcomponent link-table [links _]
  (render [_]
    (d/table {:class "table table-bordered table-hover"}
      (d/thead (d/tr (d/th "rel") (d/th "target")))
      (om/build-all link-row-group links))))

;; ---- Embedded -----------------------------------------------------------------

(defn render-empty-embedded-row [rel]
  (d/tr (d/td (str rel)) (d/td "<empty>")))

(defn render-embedded-self-link [rep owner]
  (if-let [link (:self (:links rep))]
    (render-link link owner)
    "<missing self link>"))

(defn render-first-embedded-row [[rel count rep] owner]
  (d/tr
    (d/td {:row-span count} (str rel))
    (d/td (render-embedded-self-link rep owner))))

(defn render-embedded-row [rep owner]
  (d/tr (d/td (render-embedded-self-link rep owner))))

(defcomponent embedded-row-group [[rel reps] owner]
  (render [_]
    (if (empty? reps)
      (d/tbody (render-empty-embedded-row rel))
      (apply d/tbody
             (render-first-embedded-row [rel (count reps) (first reps)] owner)
             (map #(render-embedded-row % owner) (rest reps))))))

(defcomponent embedded-table [embedded _]
  (render [_]
    (apply d/table {:class "table table-bordered table-hover"}
           (d/thead (d/tr (d/th "rel") (d/th "target")))
           (om/build-all embedded-row-group embedded))))

;; ---- Query -----------------------------------------------------------------

(defn upper-first-char [s]
  (->> (str/split s #"[-.]")
       (map #(apply str (str/upper-case (str (first %))) (rest %)))
       (str/join " ")))

(defn kw->label [key]
  (if-let [ns (namespace key)]
    (str (upper-first-char ns) " " (upper-first-char (name key)))
    (upper-first-char (name key))))

(defcomponent query-group [[key param] _ {:keys [query-key]}]
  (render [_]
    (let [required (not (:optional param))]
      (d/div {:class (build-class "form-group"
                                  (when required "required")
                                  (when (:error param) "has-error"))}
        (d/label {:class "control-label" :for (form-control-id query-key key)}
                 (kw->label key))
        (cond
          :else
          (d/input {:class "form-control"
                    :id (form-control-id query-key key)
                    :type "text"
                    :value (:raw-value param)
                    :placeholder (-> param :type deref s/explain pr-str)
                    :on-change (value-updater param)}))
        (when-let [error (:error param)]
          (d/span {:class "help-block"} (pr-str error)))
        (when-let [desc (or (:label param))]
          (d/span {:class "help-block"} desc))))))

(defn- build-query-groups [key query]
  (om/build-all query-group (:params query) {:opts {:query-key key}}))

(defcomponent query [[key query] owner {:keys [topic]}]
  (render [_]
    (d/div {:style {:margin-bottom "10px"}}
      (d/h4 (or (:label query) (kw->label key)))
      (d/form
        (apply d/div (build-query-groups key query))
        (d/button {:class "btn btn-primary" :type "submit"
                   :on-click (h (bus/publish! owner topic [key @query]))}
                  "Submit")))))

(defcomponent query-list [queries _ opts]
  (render [_]
    (apply d/div (om/build-all query queries {:opts opts}))))

;; ---- Rep -------------------------------------------------------------------

(defn del-msg [doc]
  {:resource (-> doc :links :self)
   :up (-> doc :links :up)})

(defn delete-button [owner doc]
  (d/button {:class "btn btn-danger pull-right"
             :type "button"
             :on-click (h (bus/publish! owner :delete (del-msg @doc)))}
            "Delete"))

(defn edit-button [data-view]
  (d/button {:class "btn btn-default pull-right"
             :type "button"
             :on-click
             (h (om/transact! data-view (partial mapv #(update % :edit not))))}
            (if (:edit (first data-view)) "Discard" "Edit")))

(defn edit
  "Merges edited data back into the normal doc structure."
  [doc]
  (reduce (fn [doc {:keys [key value]}]
            (assoc-in doc [:data key] value))
          doc
          (:data-view doc)))

(defn update-msg [doc]
  (dissoc (edit doc) :data-view :links-view :embedded-view))

(defn submit-button [owner doc]
  (d/button {:class "btn btn-primary pull-right"
             :type "button"
             :on-click (h (bus/publish! owner :update (update-msg @doc)))}
            "Submit"))

(defcomponent rep [doc owner]
  (render [_]
    (d/div
      (when (and (contains? (:ops doc) :delete)
                 (not (:edit (first (:data-view doc)))))
        (delete-button owner doc))
      (when (:edit (first (:data-view doc)))
        (submit-button owner doc))
      (when (and (contains? (:ops doc) :update) (get-in doc [:embedded :profile])
                 (seq (:data-view doc)))
        (edit-button (:data-view doc)))
      (d/h3 "Data")
      (if (seq (:data-view doc))
        (om/build data-table (:data-view doc))
        (d/div {:class "border"}
          (d/p "No application-specific data available.")))

      (d/h3 "Links")
      (if (seq (:links-view doc))
        (om/build link-table (:links-view doc))
        (d/div {:class "border"}
          (d/p "No queries available.")))

      (d/h3 "Embedded")
      (if (seq (:embedded-view doc))
        (om/build embedded-table (:embedded-view doc))
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
          (d/p "No forms available."))))))

;; ---- App -------------------------------------------------------------------

(defcomponent app [app-state owner]
  (will-mount [_]
    (fetch-loop app-state owner)
    (execute-query-loop app-state owner)
    (create-loop app-state owner)
    (update-loop app-state owner)
    (delete-loop app-state owner)
    (figwheel-reload-loop owner))
  (will-unmount [_])
  (render [_]
    (d/div {:class "container"}
      (d/div {:class "row"}
        (d/div {:class "col-md-12"}
          (om/build location-bar (:location-bar app-state))))
      (om/build alert/alert (:alert app-state))
      (when-let [doc (:doc app-state)]
        (om/build rep doc)))))

(om/root app app-state
  {:target (dom/getElement "app")
   :shared {:event-bus (bus/init-bus)}})
