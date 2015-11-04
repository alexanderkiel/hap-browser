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
            [schema.core :as s :refer [Str Keyword] :include-macros true]
            [schema.coerce :as c]
            [schema.utils]))

(enable-console-print!)
(s/set-fn-validation! js/enableSchemaValidation)

;; TODO: remove when https://github.com/Prismatic/schema/pull/262 is merged
(extend-protocol s/Schema
  function
  (explain [this]
    (if-let [more-schema (schema.utils/class-schema this)]
      (s/explain more-schema)
      (condp = this
        js/Boolean 'Bool
        js/Number 'Num
        js/Date 'Inst
        cljs.core/UUID 'Uuid
        this))))

(defonce app-state
  (atom
    {:alert {}
     :location-bar {}
     :headers [{:name "" :value ""}]}))

(defonce figwheel-reload-ch
  (let [ch (chan)]
    (events/listen (.-body js/document) "figwheel.js-reload" #(put! ch %))
    ch))

(defn figwheel-reload-loop [owner]
  (go-loop []
    (when (<! figwheel-reload-ch)
      (om/refresh! owner)
      (recur))))

(def ParamList
  [[(s/one Keyword "id") (s/one hap/Param "param")]])

(s/defn convert-params :- ParamList [params :- hap/Params]
  (vec params))

(def Query
  (assoc hap/Query (s/optional-key :params) ParamList))

(def QueryList
  [[(s/one Keyword "id") (s/one Query "query")]])

(s/defn convert-queries :- QueryList [queries :- hap/Queries]
  (vec (for [[id query] queries]
         [id (update query :params (fnil convert-params {}))])))

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

(defn- build-links-view [links]
  (-> (into [] util/make-vals-sequential (select-keys links [:up :self]))
      (into util/make-vals-sequential (dissoc links :up :self))))

(defn convert-doc
  "Convert all forms like {:id-1 {} :id-2 {}} into [[:id-1 {}] [:id-2 {}]]
  because we need lists of things instead of maps for om."
  [doc]
  (-> doc
      (assoc :data-view (create-data doc))
      (assoc :links-view (build-links-view (:links doc)))
      (update :queries (fnil convert-queries {}))
      (update :forms (fnil convert-queries {}))
      (assoc :embedded-view (into [] util/make-vals-sequential (:embedded doc)))))

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

(s/defn fetch-and-resolve-profile [resource headers :- hap/CustomRequestHeaders]
  (go-try
    (let [doc (<? (hap/fetch resource {:headers headers}))]
      (<? (resolve-profile doc)))))

(s/defn mk-headers :- hap/CustomRequestHeaders
  [headers :- [{:name Str :value Str}]]
  (->> (remove (comp str/blank? :name) headers)
       (group-by :name)
       (map-vals #(str/join "," (map :value %)))))

(defn fetch-loop
  "Listens on the :fetch topic. Tries to fetch the resource."
  [app-state owner]
  (bus/listen-on owner :fetch
    (fn [resource]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (->> (<? (fetch-and-resolve-profile
                     resource (mk-headers (:headers @app-state))))
               (set-uri-and-doc! app-state resource))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str (.-message e)
                                           (when-let [status (:status ex-data)]
                                             (str " Status was " status ".")))))
              (unexpected-error! owner e))))))))

(s/defn execute-query [query :- Query headers :- hap/CustomRequestHeaders]
  (hap/execute query (map-vals :value (:params query)) {:headers headers}))

(defn execute-query-loop
  "Listens on the :execute-query topic. Tries to execute the query."
  [app-state owner]
  (bus/listen-on owner :execute-query
    (fn [[_ query]]
      (alert/close! owner)
      (util/scroll-to-top)
      (go
        (try
          (->> (<? (execute-query query (mk-headers (:headers @app-state))))
               (set-uri-and-doc! app-state nil))
          (catch js/Error e
            (if-let [ex-data (ex-data e)]
              (if-let [doc (:body ex-data)]
                (set-uri-and-doc! app-state nil doc)
                (alert! owner :danger (str "Fetch error: " (.-message e))))
              (unexpected-error! owner e))))))))

(s/defn to-args :- hap/Args [params :- hap/Params]
  (for-map [[id {:keys [value]}] params
            :when value]
    id value))

(s/defn create [form :- hap/Form headers :- hap/CustomRequestHeaders]
  (hap/create form (to-args (:params form)) {:headers headers}))

(s/defn create-and-fetch [form :- hap/Form headers :- hap/CustomRequestHeaders]
  (go-try
    (let [resource (<? (create form headers))]
      (<? (fetch-and-resolve-profile resource headers)))))

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
          (let [doc (<? (create-and-fetch form (mk-headers (:headers @app-state))))]
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

;; ---- Location Bar ----------------------------------------------------------

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

;; ---- Headers ---------------------------------------------------------------

(defcomponent header [header owner]
  (render [_]
    (d/div {:class "form-group"}
      (d/div {:class "col-sm-4"}
        (d/input {:type "text" :class "form-control" :placeholder "Header"
                  :value (:name header)
                  :on-change #(om/update! header :name (util/target-value %))}))
      (d/div {:class "col-sm-4"}
        (d/input {:type "text" :class "form-control" :placeholder "Value"
                  :value (:value header)
                  :on-change #(om/update! header :value (util/target-value %))}))
      (d/div {:class "col-sm-4" :style {:padding "6px 0"}}
        (d/span {:class "fa fa-minus-circle"
                 :style {:font-size "150%"}
                 :title "Remove Header"
                 :role "button"
                 :on-click (h (bus/publish! owner :remove-header {:idx (:idx header)}))})
        (d/span {:class "fa fa-plus-circle"
                 :style {:font-size "150%"
                         :margin-left 10}
                 :title "Add Header"
                 :role "button"
                 :on-click (h (bus/publish! owner :add-header {}))})))))

(defn add-header-loop
  "Listens on the :add-header topic. Adds a new blank header to the list of
  headers."
  [headers owner]
  (bus/listen-on owner :add-header
    (fn [_]
      (om/transact! headers #(conj % {})))))

(defn remove-header-loop
  "Listens on the :remove-header topic. removes a new blank header to the list
  of headers."
  [headers owner]
  (bus/listen-on owner :remove-header
    (fnk [idx]
      (om/transact! headers #(into (subvec % 0 idx) (subvec % (inc idx)))))))

(defcomponent headers [headers owner]
  (will-mount [_]
    (add-header-loop headers owner)
    (remove-header-loop headers owner))
  (will-unmount [_]
    (bus/unlisten-all owner))
  (render [_]
    (apply d/div {:class "form-horizontal"}
      (om/build-all header (map-indexed #(assoc %2 :idx %1) headers)))))

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
          coercer (c/coercer (util/deref-cursor (:type param)) bc/coercion-matcher)
          val (coercer raw-val)]
      (om/update! param :raw-value raw-val)
      (if (schema.utils/error? val)
        (om/transact! param #(assoc % :error (schema.utils/error-val val)
                                      :value nil))
        (om/transact! param #(assoc % :error nil
                                      :value val))))))

(defn format-type [type]
  (let [type (util/deref-cursor type)]
    (if (satisfies? s/Schema type)
      (pr-str (s/explain type))
      "<unsupported>")))

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
                        :placeholder (format-type type)
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
                 (or (:label param) (kw->label key)))
        (cond
          :else
          (d/input {:class "form-control"
                    :id (form-control-id query-key key)
                    :type "text"
                    :value (:raw-value param)
                    :placeholder (-> param :type format-type)
                    :on-change (value-updater param)}))
        (when-let [error (:error param)]
          (d/span {:class "help-block"} (pr-str error)))
        (when-let [desc (:desc param)]
          (d/span {:class "help-block"} desc))))))

(defn- build-query-groups [key query]
  (om/build-all query-group (:params query) {:opts {:query-key key}}))

(s/defn to-form :- hap/Form [query :- Query]
  (update query :params (partial into {})))

(defcomponent query [[key query] owner {:keys [topic]}]
  (render [_]
    (d/div {:style {:margin-bottom "10px"}}
      (d/h4 (or (:label query) (kw->label key)))
      (when-let [desc (:desc query)]
        (d/p {:class "text-muted"} desc))
      (d/form
        (apply d/div (build-query-groups key query))
        (d/button
          {:class "btn btn-primary" :type "submit"
           :on-click (h (bus/publish! owner topic [key (to-form @query)]))}
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
  (will-unmount [_]
    (bus/unlisten-all owner))
  (render [_]
    (d/div {:class "container"}
      (d/div {:class "row"}
        (d/div {:class "col-md-12"}
          (om/build location-bar (:location-bar app-state))))
      (d/div {:class "row"}
        (d/div {:class "col-md-12"}
          (om/build headers (:headers app-state))))
      (om/build alert/alert (:alert app-state))
      (when-let [doc (:doc app-state)]
        (om/build rep doc)))))

(om/root app app-state
  {:target (dom/getElement "app")
   :shared {:event-bus (bus/init-bus)}})
