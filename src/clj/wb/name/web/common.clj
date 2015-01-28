(ns wb.name.web.common
  (:use hiccup.core
        wb.name.web.bits)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [wb.name.history :as hist]
            [wb.name.connection :refer [con]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.io :refer [piped-input-stream]])
  (:import [java.io PrintWriter OutputStreamWriter]))

(defn last-id [domain]
  (let [[last-id temp]
          (q '[:find [?last ?temp]
               :in $ ?domain
               :where [?dom :domain/name ?domain]
                      [?dom :domain/last-id ?last]
                      [?dom :domain/template ?temp]]
             (db con) domain)]
    (page
     [:div.block
      [:h3 "Last " (.toLowerCase domain)]
      [:p "The last " domain
          " id allocated was: "
          (format temp last-id)]])))

             
