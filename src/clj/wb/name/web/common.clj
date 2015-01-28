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


(defn lookup
  "Look up an object using either a primary of a secondary identifier."
  [domain db name]
  (q '[:find [?primary ...]
       :in $ % ?domain ?name
       :where (has-name ?obj ?name)
              [?dom :domain/name ?domain]
              [?obj :object/domain ?dom]
              [?obj :object/name ?primary]]
     db
     '[[(has-name ?obj ?name) [?obj :object/name ?name]]
       [(has-name ?obj ?name) [?sec :object.secondary/name ?name]
                              [?obj :object/secondary ?sec]]]
     domain
     name))

(defmulti link (fn [domain _] domain))

(defmethod link "Gene" [_ id]
  [:a {:href (str "/query-gene?lookup=" id)} id])

(defmethod link "Variation" [_ id]
  [:a {:href (str "/query-variation?lookup=" id)} id])

(defmethod link "Feature" [_ id]
  [:a {:href (str "/query-feature?lookup=" id)} id])

(defn lc [^String s]
  (.toLowerCase s))

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

             

(defn do-kill-object [domain id]
  (let
      [db    (db con)
       cid   (first (lookup domain db id))
       errs  (->> [(if-not cid
                     (str id " does not exist"))]
                  (filter identity)
                  (seq))]
    (if errs
      {:err errs}
      (let [txn [[:wb/ensure-max-t [:object/name cid] (d/basis-t db)]
                 [:db/add [:object/name cid] :object/live false]
                 (txn-meta)]]
        (try
          (let [txr @(d/transact con txn)]
            {:done true
             :canonical cid})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))
        
(defn kill-object [domain {:keys [id reason]}]
  (page
   (let [result (if id
                  (do-kill-object domain id))]
     (if (:done result)
       [:div.block
        [:h3 "Kill " (lc domain)]
        [:p (link domain (:canonical result)) " has been killed."]]
       [:div.block
        [:form {:method "POST"}
         (anti-forgery-field)
         [:h3 "Kill " (lc domain)]
         (for [err (:err result)]
           [:p.err err])
         [:table.info
          [:tr
           [:th "Enter ID to kill"]
           [:td
            [:input {:type "text"
                     :name "id"
                     :class "autocomplete"
                     :size 20
                     :maxlength 20
                     :value (or id "")}]]]
          [:tr
           [:th "Reason for removal"]
           [:td
            [:input {:type "text"
                     :name "reason"
                     :size 40
                     :maxlength 200
                     :value (or reason "")}]]]]
         [:input {:type "submit"}]]]))))
