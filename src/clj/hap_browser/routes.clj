(ns hap-browser.routes
  (:require [compojure.core :as compojure :refer [GET]]
            [compojure.route :as route]
            [ring.util.response :refer [file-response]]))

(defn index-html [dev]
  (if dev
    "public/index-dev.html"
    "public/index.html"))

;; ---- Routes ----------------------------------------------------------------

(defn routes [dev]
  (compojure/routes
    (GET "/" []
      (file-response (index-html dev) {:root "resources"}))

    (GET "/w/:id" []
      (file-response (index-html dev) {:root "resources"}))

    (route/files "/" {:root "resources/public"})))
