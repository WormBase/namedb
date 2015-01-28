(ns wb.name.web.variation
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

;;
;; Query gene info
;;

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

(defn query [{:keys [lookup include-tx]}]
  (page
   [:div.block
    [:form {:method "GET"}
     [:h3 "Find variation"]

     [:table.info
      [:tr
       [:th "Variation to retrieve"]
       [:td [:input {:type "text"
                     :name "lookup"
                     :class "autocomplete"
                     :size 20
                     :maxlength 20
                     :value (or lookup "")}]]]]
     [:input {:type "submit" :value "Search"}]]]
   (if lookup
    [:div.block
     (let [db (db con)
           ids (lookup-variation db lookup)]
       (if (seq ids)
        (let [gene (entity db [:object/name (ffirst ids)])]
         (list
          [:table.info
           [:tr
            [:th "Variation"]
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
                  (hist/gene-history (d/history db) (ffirst ids))]
              [:tr
               (if include-tx
                 [:td tx])
               [:td what]
               [:td who]
               [:td when]
               [:td type]
               [:td name]
               [:td (if related
                      [:a {:href (str "?lookup=" related)}
                       related])]])]]))
         (list
          lookup " does not exist in database.")))])))
