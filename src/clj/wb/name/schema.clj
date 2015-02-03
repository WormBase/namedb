(ns wb.name.schema
  (:use datomic-schema.schema)
  (:require [datomic.api :refer (tempid)]))

(def name-server-schema
 (concat
  (generate-schema tempid
                   
   [
    (schema domain
     (fields
      [name :string :unique-identity]
      [template :string]
      [last-id :long :nohistory]))
      

    (schema name-type
     (fields
      [domain :ref]        
      [name :string]
      [unique :boolean]
      [primary :boolean]))

    (schema object
     (fields
      [domain :ref]
      [name :string :unique-identity]  ;; Making this an identity makes our life easy
                                       ;; but could potentially cause trouble if two domains have
                                       ;; similar templates.
      [version :long]
      [live :boolean]
      [secondary :ref :component :many]
      [split :ref :many]
      [merge :ref]))

    (schema object.secondary
     (fields
      [name-type :ref]
      [name :string :indexed]))


    ;; A&A stuff

    (schema user
     (fields
      [name :string :unique-identity]
      [email :string :unique-identity]
      [role :enum [:view :edit :cgc :receive-email
                   :query :transact] :many]
      [wbperson :string]
      [x500-cn :string :unique-identity]
      [bcrypt-passwd :string :nohistory]))

   ;; transaction annotation

    (schema nametxn
     (fields
      [user :string]
      [type :enum [:import]]))
    
    ])

  [{:db/id #db/id[:db.part/tx]
    :db/txInstant #inst "1970-01-01T00:00:01"}]))


(def seed-data
  [{:db/id #db/id[:db.part/user -2001]
    :domain/name "Gene"
    :domain/template "WBGene%08d"
    :domain/last-id 255596}

   {:db/id #db/id[:db.part/user -2002]
    :domain/name "Feature"
    :domain/template "WBsf%06d"
    :domain/last-id 977536}

   {:db/id #db/id[:db.part/user -2003]
    :domain/name "Variation"
    :domain/template "WBVar%08d"
    :domain/last-id 2141074}


   {:db/id #db/id[:db.part/user]
    :name-type/domain #db/id[:db.part/user -2001]
    :name-type/name "CGC"
    :name-type/unique true
    :name-type/primary true}

   {:db/id #db/id[:db.part/user]
    :name-type/domain #db/id[:db.part/user -2001]
    :name-type/name "CDS"
    :name-type/unique false
    :name-type/primary true}

   {:db/id #db/id[:db.part/user]
    :name-type/domain #db/id[:db.part/user -2001]
    :name-type/name "Sequence"
    :name-type/unique true
    :name-type/primary true}

   {:db/id #db/id[:db.part/user]
    :name-type/domain #db/id[:db.part/user -2001]
    :name-type/name "Public_name"
    :name-type/unique true
    :name-type/primary true}

   {:db/id #db/id[:db.part/user]
    :name-type/domain #db/id[:db.part/user -2003]
    :name-type/name "Public_name"
    :name-type/unique true
    :name-type/primary true}

   {:db/id #db/id[:db.part/user]
    :user/email "thomas.down@wormbase.org"
    :user/wbperson "WBPerson12021"
    :user/role #{:user.role/view :user.role/edit :user.role/cgc}}
    
   {:db/id #db/id[:db.part/tx]
    :db/txInstant #inst "1970-01-01T00:00:01"}

   ])

(def db-fns
  [{:db/id #db/id[:db.part/user]
    :db/ident :wb/ensure-max-t
    :db/fn #db/fn {:lang "clojure"
                   :params [db ent t]
                   :code (let [max-t (q '[:find (max ?t) .
                                          :in $ ?id
                                          :where [?id _ _ ?tx]
                                                 [(datomic.api/tx->t ?tx) ?t]]
                                        db ent)]
                           (when (> max-t t)
                             (throw (Exception. (str "Entity " ent " has maximum t of " max-t " which is greater than " t)))))}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/new-obj
    :db/fn #db/fn {:lang "clojure"
                   :params [db domain ids]
                   :code (let [[domain-id last-id temp] (q '[:find [?dom ?last ?temp] :in $ ?dom-name
                                                             :where [?dom :domain/name ?dom-name]
                                                                    [?dom :domain/last-id ?last]
                                                                    [?dom :domain/template ?temp]]
                                                                
                                                           db domain)]
                           (when-not domain-id
                             (throw (Exception. (str "Unknown domain " domain))))
                           (concat
                            (map-indexed
                             (fn [idx tid]
                               {:db/id tid
                                :object/domain domain-id
                                :object/name (format temp (+ last-id idx 1))
                                :object/live true})
                             ids)
                            
                            [[:db.fn/cas domain-id :domain/last-id last-id (+ last-id (count ids))]]))}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/add-name
    :db/fn #db/fn {:lang "clojure"
                   :params [db id domain type name]
                   :code (let [name-type-id (q '[:find ?nt . :in $ ?dom-name ?name-type
                                                 :where [?dom :domain/name ?dom-name]
                                                        [?nt :name-type/domain ?dom]
                                                        [?nt :name-type/name ?name-type]]
                                               db domain type)]
                           (when-not name-type-id
                             (throw (Exception. (str "Unknown name-type " domain ":" type))))
                           (if-let [current (q '[:find ?id .
                                                 :in $ ?name-type ?name
                                                 :where [?sec :object.secondary/name ?name]
                                                        [?sec :object.secondary/name-type ?name-type]
                                                        [?obj :object/secondary ?sec]
                                                        [?obj :object/name ?id]]
                                               db name-type-id name)]
                             (throw (Exception. (str "Name " type ":" name " already used for " current ", can't add to " id))))
                           [{:db/id id
                             :object/secondary {
                              :object.secondary/name-type name-type-id
                              :object.secondary/name name}}]
                           )}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/add-name-quiet
    :db/fn #db/fn {:lang "clojure"
                   :params [db id domain type name]
                   :code (let [name-type-id (q '[:find ?nt . :in $ ?dom-name ?name-type
                                                 :where [?dom :domain/name ?dom-name]
                                                        [?nt :name-type/domain ?dom]
                                                        [?nt :name-type/name ?name-type]]
                                               db domain type)]
                           (when-not name-type-id
                             (throw (Exception. (str "Unknown name-type " domain ":" type))))
                           [{:db/id id
                             :object/secondary {
                              :object.secondary/name-type name-type-id
                              :object.secondary/name name}}]
                           )}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/change-name
    :db/fn #db/fn {:lang "clojure"
                   :params [db id domain type new-name]
                   :code (let [name-type-id (q '[:find ?nt . :in $ ?dom-name ?name-type
                                                 :where [?dom :domain/name ?dom-name]
                                                        [?nt :name-type/domain ?dom]
                                                        [?nt :name-type/name ?name-type]]
                                               db domain type)]
                           (when-not name-type-id
                             (throw (Exception. (str "Unknown name-type " domain ":" type))))

                           (concat
                            (for [name-holder (q '[:find [?n ...]
                                                   :in $ ?obj ?nt
                                                   :where [?obj :object/secondary ?n]
                                                          [?n :object.secondary/name-type ?nt]]
                                                 db id name-type-id)]
                              [:db.fn/retractEntity name-holder])
                            [{:db/id id
                             :object/secondary {
                              :object.secondary/name-type name-type-id
                              :object.secondary/name new-name}}])
                           )}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/retract-name
    :db/fn #db/fn {:lang "clojure"
                   :params [db id type name]
                   :code (let [name-holder (q '[:find ?sec .
                                                :in $ ?id ?name-type ?name
                                                :where [?id :object/secondary ?sec]
                                                       [?sec :object.secondary/name-type ?nt]
                                                       [?nt :name-type/name ?name-type]
                                                       [?sec :object.secondary/name ?name]]
                                              db id type name)]
                           (when-not name-holder
                             (throw (Exception. (str "No current name " id ":" type ":" name))))
                           (if name-holder
                             [[:db.fn/retractEntity name-holder]]))}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/retract-name-quiet
    :db/fn #db/fn {:lang "clojure"
                   :params [db id type name]
                   :code (let [name-holder (q '[:find ?sec .
                                                :in $ ?id ?name-type ?name
                                                :where [?id :object/secondary ?sec]
                                                       [?sec :object.secondary/name-type ?nt]
                                                       [?nt :name-type/name ?name-type]
                                                       [?sec :object.secondary/name ?name]]
                                              db id type name)]
                           (if name-holder
                             [[:db.fn/retractEntity name-holder]]))}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/merge
    :db/fn #db/fn {:lang "clojure"
                   :params [db id idx]
                   :code [[:db/add idx :object/merge id]
                          [:db/add idx :object/live false]]}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/split
    :db/fn #db/fn {:lang "clojure"
                   :params [db id new-id]
                   :code (let [[domain-id last-id temp]
                               (q '[:find [?domain ?last-id ?temp]
                                    :in $ ?id
                                    :where [?id :object/domain ?domain]
                                           [?domain :domain/last-id ?last-id]
                                           [?domain :domain/template ?temp]]
                                  db id)]
                           [{:db/id new-id
                             :object/domain domain-id
                             :object/name (format temp (inc last-id))
                             :object/live true}

                            [:db.fn/cas domain-id :domain/last-id last-id (inc last-id)]
                            [:db/add id :object/split new-id]])}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/kill
    :db/fn #db/fn {:lang "clojure"
                   :params [db id]
                   :code [[:db/add id :object/live false]]}}

   {:db/id #db/id[:db.part/user]
    :db/ident :wb/resurrect
    :db/fn #db/fn {:lang "clojure"
                   :params [db id]
                   :code [[:db/add id :object/live true]]}}
                   
   
  {:db/id #db/id[:db.part/tx]
   :db/txInstant #inst "1970-01-01T00:00:01"}

   ])
