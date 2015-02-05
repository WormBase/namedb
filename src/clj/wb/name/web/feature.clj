(ns wb.name.web.feature
  (:use hiccup.core
        wb.name.web.bits
        wb.name.mail)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [wb.name.history :as hist]
            [wb.name.connection :refer [con]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.io :refer [piped-input-stream]])
  (:import [java.io PrintWriter OutputStreamWriter]))

(defn do-new []
  (let [temp (d/tempid :db.part/user)
        txn  [[:wb/new-obj "Feature" [temp]]
              (txn-meta)]]
    (try
      (let [txr @(d/transact con txn)
            db  (:db-after txr)
            ent (touch (entity db (d/resolve-tempid db (:tempids txr) temp)))]
        (ns-email
         (format "New feature - %s" (:object/name ent))
         "ID"    (:object/name ent))
        {:done (:object/name ent)})
      (catch Exception e {:err [(.getMessage e)]}))))
        

(defn new [{:keys [confirm]}]
  (let [result (if confirm
                 (do-new))]
    (page 
     (if (:done result)
       [:div.block
        [:h3 "New feature"]
        [:p "Feature ID " (link "Feature" (:done result)) " has been generated."]]
       [:div.block
        [:h3 "New feature"]
        (for [err (:err result)]
          [:p.err err])
        [:form {:method "POST"}
         (anti-forgery-field)
         [:input {:type "hidden"
                  :name "confirm"
                  :value "yes"}]
         [:input {:type "submit"}]]]))))
