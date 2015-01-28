(ns wb.name.history
  (:require [datomic.api :as d :refer (q db history touch entity)]))

(defn vec->map [keys values]
  (->> (map vector keys values)
       (into {})))

(defn- gene-history-name
  [db id]
  (->>
   (q '[:find ?name ?type ?flag ?who ?when ?tx
        :in $ ?prim
        :where [?obj :object/name ?prim]
               [?obj :object/secondary ?sec ?tx ?flag]
               [?sec :object.secondary/name ?name]
               [?sec :object.secondary/name-type ?nt]
               [?nt :name-type/name ?type]
               [?tx :db/txInstant ?when]
               [(get-else $ ?tx :nametxn/user "unknown") ?who]]
      db id)
   (map (partial vec->map [:name :type :flag :who :when :tx]))
   (sort-by :tx)
   (partition-by :tx)
   (mapcat
    (fn [txl]
      (for [[type txns] (group-by :type txl)
                :let [{adds     true
                       retracts false} (group-by :flag txns)
                      {:keys [who when tx]} (first txns)]]
            (cond
             (or (> (count adds) 1) (> (count retracts) 1))
             {:who who :when when :tx tx :what :odd}

             (and (seq adds) (seq retracts))
             {:who who :when when :tx tx :what :changeName
              :type type :name (:name (first adds))}

             (seq adds)
             {:who who :when when :tx tx :what :addName
              :type type :name (:name (first adds))}

             (seq retracts)
             {:who who :when when :tx tx :what :delName 
              :type type :name (:name (first retracts))}))))))

(defn- gene-merge-ins [db id]
  (->>
    (q '[:find ?idx ?who ?when ?tx
         :in $ ?id
         :where [?obj :object/name ?id]
                [?objx :object/merge ?obj ?tx true]
                [?objx :object/name ?idx]
                [?tx :db/txInstant ?when]
                [(get-else $ ?tx :nametxn/user "unknown") ?who]]
       db id)
    (map (fn [[idx who when tx]]
           {:who who
            :when when
            :what "mergedFrom"
            :tx tx
            :related idx}))))

(defn- gene-merge-outs [db id]
  (->>
    (q '[:find ?idx ?who ?when ?tx
         :in $ ?id
         :where [?obj :object/name ?id]
                [?obj :object/merge ?objx ?tx true]
                [?objx :object/name ?idx]
                [?tx :db/txInstant ?when]
                [(get-else $ ?tx :nametxn/user "unknown") ?who]]
       db id)
    (map (fn [[idx who when tx]]
           {:who who
            :when when
            :what "mergedTo"
            :tx tx
            :related idx}))))

(defn- gene-split-to [db id]
  (->>
   (q '[:find ?idx ?who ?when ?tx
        :in $ ?id
        :where [?obj :object/name ?id]
               [?obj :object/split ?objx ?tx true]
               [?objx :object/name ?idx]
               [?tx :db/txInstant ?when]
               [(get-else $ ?tx :nametxn/user "unknown") ?who]]
      db id)
   (map (fn [[idx who when tx]]
          {:who who
           :when when
           :what "splitTo"
           :tx tx
           :related idx}))))

(defn- gene-split-from [db id]
  (->>
   (q '[:find ?idx ?who ?when ?tx
        :in $ ?id
        :where [?obj :object/name ?id]
               [?objx :object/split ?obj ?tx true]
               [?objx :object/name ?idx]
               [?tx :db/txInstant ?when]
               [(get-else $ ?tx :nametxn/user "unknown") ?who]]
      db id)
   (map (fn [[idx who when tx]]
          {:who who
           :when when
           :what "splitFrom"
           :tx tx
           :related idx}))))

(defn- gene-history-liveness
  [db id]
  (let [create-tx   (q '[:find ?tx .
                         :in $ ?prim
                         :where [?obj :object/name ?prim ?tx true]]
                       db id)
        merge-ins   (gene-merge-ins db id)
        merge-outs  (gene-merge-outs db id)
        split-tos   (gene-split-to db id)
        split-froms (gene-split-from db id)
        suppress-tx (set (map :tx (concat merge-outs split-froms)))]
    (->>
     (q '[:find ?live ?who ?when ?tx
          :in $ ?prim
          :where [?obj :object/name ?prim]
                 [?obj :object/live ?live ?tx true]   ;; Retractions not interesting here
                 [?tx  :db/txInstant ?when]
                 [(get-else $ ?tx :nametxn/user "unknown") ?who]]
        db id)
     (map
      (fn [[live? who when tx]]
        {:who  who
         :when when
         :what (if live? 
                 (if (= tx create-tx)
                   "created"
                   "resurrected")
                 "killed")
         :tx tx}))
     (filter #(not (suppress-tx (:tx %))))
     (concat merge-ins merge-outs split-tos split-froms))))
      

(defn gene-history
  "Return an ordered list of history events for a gene.  Requires a history DB."
  [db id]
  (->> (concat (gene-history-liveness db id)
               (gene-history-name db id))
       (sort-by :tx)))
