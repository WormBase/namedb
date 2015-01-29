(ns wb.name.import
  (:use clojure.set
        clojure.java.io)
  (:require [clojure.java.jdbc :as j]
            [datomic.api :as d]
            [clojure.string :as str]
            [clojure.instant :refer (read-instant-date)]))

(defn conj-if [col x]
  (if x
    (conj col x)
    col))


(defn import-log [{:keys [primary-names known-names cur-names domains name-types]}
                  {:keys [object_id log_what log_version log_related_object log_name_type log_name_value domain_id]}]
 (let [domain (if domain_id
                (domains domain_id)
                "Gene")]
  (case log_what
    "import"
    (let [tid (d/tempid :db.part/user)]
      (concat
       [{:db/id tid
         :object/domain [:domain/name domain]
         :object/name (primary-names object_id)
         :object/live true}]
       (for [ntid (keys name-types)
             :let [key (str object_id "-" ntid)
                   cur (cur-names key)]
             :when (and cur (not (known-names key)))]
         [:wb/add-name-quiet tid domain (name-types ntid) cur])))

    "created"
    [{:db/id (d/tempid :db.part/user)
      :object/domain [:domain/name domain]
      :object/name (primary-names object_id)
      :object/live true}]

    "splitFrom"
    [{:db/id (d/tempid :db.part/user)
      :object/domain [:domain/name domain]
      :object/name (primary-names object_id)
      :object/_split [:object/name (primary-names log_related_object)]
      :object/live true}]

    "mergedTo"
    [{:db/id [:object/name (primary-names object_id)]
      :object/merge [:object/name (primary-names log_related_object)]
      :object/live false}]

    "addName"
    (when-not (nil? log_name_value)
      (if (name-types log_name_type)
        [[:wb/change-name [:object/name (primary-names object_id)] domain (name-types log_name_type) log_name_value]]))

    "changeName"
    (when-not (nil? log_name_value)
      (if (name-types log_name_type)
        [[:wb/change-name [:object/name (primary-names object_id)] domain (name-types log_name_type) log_name_value]]))

    "delName"
    (if (name-types log_name_type)
      [[:wb/retract-name-quiet [:object/name (primary-names object_id)] domain (name-types log_name_type)]])
    
    "killed"
    [[:db/add [:object/name (primary-names object_id)] :object/live false]]

    "resurrected"
    [[:db/add [:object/name (primary-names object_id)] :object/live true]]

    ;;default
    nil)))
     
    

(defn import-tx* [imp logs]
  (when-let [tx (seq (mapcat (partial import-log imp) logs))]
    @(d/transact (:con imp) (conj tx {:db/id #db/id[:db.part/tx]
                                      :db/txInstant (:log_when (first logs))
                                      :nametxn/user (:log_who (first logs))}))))

(def is-create? #{"created" "splitFrom" "import"})

(defn import-tx [imp logs]
  ;; Moved "created" out to a separate transaction so we can use lookup-refs in add.
  (import-tx* imp (filter #(is-create? (:log_what %)) logs))
  (import-tx* imp (filter #(not (is-create? (:log_what %))) logs)))

(defn import-resultset [imp include-fn]
  (fn [rs]
    (->> (reduce
          (fn [{:keys [who when] :as state} {:keys [log_who log_when object_id] :as lr}]
            (if (include-fn object_id)
              (if (and (= who log_who)
                       (= when log_when))
                (update-in state [:logs] conj lr)
                (do
                  (when-let [logs (seq (:logs state))]
                    (doseq [blk (partition-all 250 logs)]
                      (import-tx imp blk)))
                  {:who log_who
                   :when log_when
                   :logs [lr]}))
              state))
          {}
          (lazy-cat rs [{:log_who "dummy"}])))))

(defn parse-int [^String s]
  (Integer/parseInt s))

(defn read-event-file [f]
  (->> (reader f)
       (line-seq)
       (map 
        (fn [line]
          (let [[obj-id log-version log-what log-who log-when log-related log-name-type log-name-value domain]
                   (str/split line #"\t")]
            {:object_id (parse-int obj-id)
             :log_version (parse-int log-version)
             :log_what log-what
             :log_who log-who
             :log_when (read-instant-date (str/replace log-when #" " "T"))
             :log_related_object (parse-int log-related)
             :log_name_type (parse-int log-name-type)
             :log_name_value (if (not= "NULL" log-name-value) log-name-value)
             :domain_id (if domain (parse-int domain))})))))

(defn update-before-create [event-stream]
  (count
   (reduce
    (fn [seen {:keys [object_id log_what log_when]}]
      (if (is-create? log_what)
        (conj seen object_id)
        (do
          (if (not (seen object_id))
            (println "Unexpected " log_what " on " object_id " at " log_when))
          seen)))
    #{}
    (mapcat
     (fn [loggroup]
       (concat
        (filter #(is-create? (:log_what %)) loggroup)
        (filter #(not (is-create? (:log_what %))) loggroup)))
     (partition-by :log_when event-stream)))))
          
                 
  
