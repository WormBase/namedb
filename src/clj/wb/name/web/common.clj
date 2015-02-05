(ns wb.name.web.common
  (:use hiccup.core
        wb.name.web.bits
        wb.name.mail)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [wb.name.history :as hist]
            [wb.name.connection :refer [con]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.io :refer [piped-input-stream]]
            [clj-time.format :as time]
            [clj-time.coerce :refer [from-date]])
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


(defn query [domain id]
  (page "Query " domain ":" id))

(defn do-kill-object [domain id reason]
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
            (ns-email (format "%s killed - %s" domain cid)
                "ID"        cid
                "Remark"    reason)
            {:done true
             :canonical cid})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))
        
(defn kill-object [domain {:keys [id reason]}]
  (page
   (let [result (if id
                  (do-kill-object domain id reason))]
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
           [:td (ac-field "id" domain id)]]
          [:tr
           [:th "Reason for removal"]
           [:td
            [:input {:type "text"
                     :name "reason"
                     :size 40
                     :maxlength 200
                     :value (or reason "")}]]]]
         [:input {:type "submit"}]]]))))

(def ^:private history-time-format (time/formatter "EEE, dd MMM yyyy HH:mm:ss"))

(defn query [domain {lookup-str :lookup include-tx :include-tx}]
  (page
   [:div.block
    [:form {:method "GET"}
     [:h3 "Find " (lc domain)]

     [:table.info
      [:tr
       [:th domain " to retrieve"]
       [:td (ac-field "lookup" domain lookup-str)]]]
     [:input {:type "submit" :value "Search"}]]]
   (if lookup-str
    [:div.block
     (let [db (db con)
           ids (lookup domain db lookup-str)]
       (if (seq ids)
        (let [gene (entity db [:object/name (first ids)])]
         (list
          [:table.info
           [:tr
            [:th domain]
            [:td (:object/name gene)]]
           [:tr
            [:th "LIVE?"]
            [:td (if (:object/live gene) "Live" "Dead")]]
           (for [sec (->> (:object/secondary gene)
                          (sort-by (comp :db/id :object.secondary/name-type)))]
             [:tr
              [:th (:name-type/name (:object.secondary/name-type sec))]
              [:td (:object.secondary/name sec)]])]
             
          
          [:table.history
           [:tr
            (if include-tx
              [:th "TX"])
            [:th "What"]
            [:th "Who"]
            [:th "When"]
            [:th "Type"]
            [:th "Name"]
            [:th "Related"]]
           [:tbody
            (for [{:keys [tx what who when type name related]}
                  (hist/gene-history (d/history db) (first ids))]
              [:tr
               (if include-tx
                 [:td tx])
               [:td what]
               [:td who]
               [:td (time/unparse history-time-format (from-date when))]
               [:td type]
               [:td name]
               [:td (if related
                      (link domain related))]])]]))
         (list
          domain ":" lookup-str " does not exist in database.")))])))


(defn do-merge-objects [domain id idx]
  (let [db   (db con)
        cid  (first (lookup domain db id))
        cidx (first (lookup domain db idx))
        obj  (entity db [:object/name cid])
        objx (entity db [:object/name cidx])
        errs (->> [(if-not cid
                     (str "No match for " id))
                   (if-not cidx
                     (str "No match for " idx))
                   (if (= (:object/live obj) false)
                     (str "Cannot merge because " cid " is not live."))
                   (if (and cid (= cid cidx))
                     (str "Cannot merge " (if (= id cid)
                                            cid
                                            (str id " (" cid ")"))
                          " into itself"))]
                  (filter identity)
                  (seq))]
    (if errs
      {:err errs}
      (let [txn [[:wb/ensure-max-t [:object/name cid]  (d/basis-t db)]
                 [:wb/ensure-max-t [:object/name cidx] (d/basis-t db)]
                 [:wb/merge [:object/name cid] [:object/name cidx]]]]
        (try
          (let [txr @(d/transact con txn)]
            (ns-email (format "Merged %ss %s (%s) - %s (%s)" (lc domain) id cid idx cidx)
                "LIVE" (format "retained %s %s" (lc domain) cid)
                "DEAD" (format "killed   %s %s" (lc domain) cidx))
            {:done true
             :cid cid
             :cidx cidx})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn merge-objects [domain {:keys [id idx]}]
  (page
   (let [result (if (and id idx)
                  (do-merge-objects domain id idx))]
     (if (:done result)
       [:div.block
        [:h3 "Merged " (lc domain) "s"]
        [:p domain " " (link domain (:cidx result)) " is DEAD and has been merged into " (link domain (:cid result))]]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Merge " (lc domain) "s"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th domain " to stay alive:"]
           [:td (ac-field "id" domain id)]]
          [:tr
           [:th domain " to remove:"]
           [:td (ac-field "idx" domain idx)]]]
         [:input {:type "submit"}]]]))))
