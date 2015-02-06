(ns wb.name.web.variation
  (:use hiccup.core
        wb.name.utils
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

(defn vlink [id]
  (link "Variation" id))

(defn lookup-variation
  "Look up an object using either a primary of a secondary identifier."
  [db name]
  (q '[:find ?primary :in $ % ?name
       :where (has-name ?obj ?name)
              [?obj :object/domain [:domain/name "Variation"]]
              [?obj :object/name ?primary]]
     db
     '[[(has-name ?obj ?name) [?obj :object/name ?name]]
       [(has-name ?obj ?name) [?sec :object.secondary/name ?name]
                              [?obj :object/secondary ?sec]]]
     name))

;;
;; Change name
;;

(defn do-change-name [id name]
  (let [db   (db con)
        cid  (ffirst (lookup-variation db id))
        errs (those
              (if-not cid
                (str "Couldn't find " id)))]
    (if errs
      {:err errs}
      (let [txn [[:wb/ensure-max-t [:object/name cid] (d/basis-t db)]
                 [:wb/change-name [:object/name cid] "Variation" "Public_name" name]
                 (txn-meta)]]
        (try
          @(d/transact con txn)
          (ns-email
           (format "WBVarID change name %s -> %s" cid name)
           "WBVarID"   cid
           "Name"      name)
          {:done true
           :canonical cid}
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn change-name [{:keys [id name]}]
  (page
   (let [result (if (and id name)
                  (do-change-name id name))]
     (if (:done result)
       [:div.block
        [:h3 "Changed variation name"]
        [:p "Variation " (vlink id) " chnaged to name " [:strong name]]]

       [:div.block
        [:h3 "Change variation name"]
        (for [err (:err result)]
          [:p.err err])
        [:form {:method "POST"}
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "ID to change"]
           [:td (ac-field "id" "Variation" id)]]
          [:tr
           [:th "Public name of variation"]
           [:td [:input {:type "text"
                         :name "name"
                         :size 20
                         :maxlength 40
                         :value (or name "")}]]]]
         [:input {:type "submit"}]]]))))

;;
;; Request a variation ID
;;

(defn do-new [name remark]
  ;; Name uniqueness gets checked by :wb/add-name
  (let [temp (d/tempid :db.part/user)
        txn  [[:wb/new-obj "Variation" [temp]]
              [:wb/add-name temp "Variation" "Public_name" name]
              (txn-meta)]]
    (try
      (let [txr @(d/transact con txn)
            db  (:db-after txr)
            ent (touch (entity db (d/resolve-tempid db (:tempids txr) temp)))]
        (ns-email
         (format "WBVarID request %s" name)
         "WBVarID" (:object/name ent)
         "Name"    name
         "Remark"  remark)
        {:done true
         :id (:object/name ent)})
      (catch Exception e {:err [(.getMessage e)]}))))

(defn new [{:keys [name remark]}]
  (page
   (let [result (if name
                  (do-new name remark))]
     (if (:done result)
       [:div.block
        [:h3 "Generated new ID"]
        [:p "New variation " (vlink (:id result)) " created with name " [:strong name]]]

       [:div.block
        [:h3 "New variation"]
        (for [err (:err result)]
          [:p.err err])
        [:form {:method "POST"}
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Public name of variation"]
           [:td [:input {:type "text"
                         :name "name"
                         :size 20
                         :maxlength 40
                         :value (or name "")}]]]
          [:tr
           [:th "Additional comment (e.g. related papers)"]
           [:td [:input {:type "text"
                         :name "remark"
                         :size 50
                         :maxlength 400
                         :value (or remark "")}]]]]
         [:input {:type "submit"}]]]))))
          

(defn dump [{:keys [confirm]}]
  (let [db (db con)]
    (if confirm
      {:headers
       {"Content-Type" "text/tab-separated-values"}
       :body
       (piped-input-stream
        (fn [out]
          (let [pw (PrintWriter. (OutputStreamWriter. out))]
            (binding [*out* pw]
              (doseq [[name pub live?]
                      (q '[:find ?name ?pub ?live
                           :where [?dom :domain/name "Variation"]
                                  [?pnt :name-type/domain ?dom]
                                  [?pnt :name-type/name "Public_name"]
                                  [?obj :object/domain ?dom]
                                  [?obj :object/name ?name]
                                  [?obj :object/secondary ?sec]
                                  [?sec :object.secondary/name-type ?pnt]
                                  [?sec :object.secondary/name ?pub]
                                  [?obj :object/live ?live]]
                         db)]
                (println
                 (str/join
                  "\t"
                  [pub
                   name
                   (if live?
                     "live"            
                     "DEAD")]))))
            (.flush pw))))}
      (page 
       [:div.block
        [:form {:method "POST"}
         [:h3 "Dump Variation IDs"]
         (anti-forgery-field)
         [:p "Are you sure you want to dump all IDs?  This can take a few seconds."]
         [:input {:type "hidden"
                  :name "confirm"
                  :value "yes"}]
         [:input {:type "submit"}]]]))))
