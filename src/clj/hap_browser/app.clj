(ns hap-browser.app
  (:require [hap-browser.routes :refer [routes]]))

(defn app [dev]
  (routes dev))

(def app-dev (app true))
