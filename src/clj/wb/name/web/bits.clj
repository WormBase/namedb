(ns wb.name.web.bits
  (:use hiccup.core)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [wb.name.history :as hist]
            [ring.util.anti-forgery :refer [anti-forgery-field]]))

(defn menu []
 (let [id friend/*identity*]
   (list 
    [:div.menu-header
     [:h3 "Name server tasks"]]

    [:div.menu-content
     [:h4 "Gene"]
     
     [:p [:a {:href "/query-gene"} "Find gene"]]
     
     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/add-gene-name"} "Add name"]])
     
     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/remove-gene-name"} "Remove name"]])    
    
     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/new-gene"} "New gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/kill-gene"} "Kill gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/merge-gene"} "Merge gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/split-gene"} "Split gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/load-gene-file"} "Load file"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/change-gene-class"} "Change class"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/dump-genes"} "Dump all genes"]])


     [:h4 "Variation"]

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/query-variation"} "Find variation"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/variation-last-id"} "Last variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-change-name"} "Change public name"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-request-id"} "Request new variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-kill"} "Kill variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-merge"} "Merge two variation IDs"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/dump-variations"} "Dump all variations"]])

     [:h4 "Feature"]

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/query-feature"} "Find feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-new"} "New feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-kill"} "Kill feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-merge"} "Merge features"]])
     
     ]

     )))

(defn page [& content]
  (html
   [:head
    [:title "Wormbase Name Server"]
    [:script {:language "javascript"
              :src "/js/nameserver.js"}]
    [:link {:rel "stylesheet"
            :href "/css/nameserver.css"}]]
   [:body
    [:div.header
     [:img.banner {:src "img/logo_wormbase_gradient_small.png"}]
     [:h1 "Name Server"]
     [:div.ident (:wbperson (friend/current-authentication))]]
    [:div.container
     (vec (cons :div.content content))
     [:div.menu (menu)]]]))

(defn txn-meta
  "Return a transaction metadata entity for the current request"
  []
  {:db/id (d/tempid :db.part/tx)
   :nametxn/user (:wbperson (friend/current-authentication))})
