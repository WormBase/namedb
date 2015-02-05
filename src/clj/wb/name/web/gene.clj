(ns wb.name.web.gene
  (:use hiccup.core
        wb.name.mail
        wb.name.web.bits)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [wb.name.history :as hist]
            [wb.name.connection :refer [con]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.io :refer [piped-input-stream]])
  (:import [java.io PrintWriter OutputStreamWriter]))

(defn update-public-name
  "Check if the public name of Gene `p` should be updated after applying
   transaction `tx` to `db`, and return a list of transaction data if needed." 
  [db tx p]
  (let [{:keys [db-after tempids]} (d/with db tx)
        rp (or (d/resolve-tempid db-after tempids p) p)    ;; returns null if p isn't a tempid
        names (->> (q '[:find ?type ?name
                        :in $ ?obj
                        :where [?obj :object/secondary ?sec]
                               [?sec :object.secondary/name ?name]
                               [?sec :object.secondary/name-type ?nt]
                               [?nt  :name-type/name ?type]]
                      db-after rp)
                   (into {}))
        old-public (names "Public_name")
        new-public (some names ["CGC" "Sequence" "Public_name"])]
    (if (and new-public
             (not= new-public old-public))
      [[:wb/change-name p "Gene" "Public_name" new-public]])))   ;; go back to using the tempid...

(def species-longnames
  {"elegans"        "Caenorhabditis elegans"
   "briggsae"       "Caenorhabditis briggsae"
   "remanei"        "Caenorhabditis remanei"
   "brenneri"       "Caenorhabditis brenneri"
   "japonica"       "Caenorhabditis japonica"
   "pristionchus"   "Pristionchus pacificus"
   "brugia"         "Brugia malayi"
   "ovolvulus"      "Onchocerca volvulus"})
                      
(def name-checks
  {"elegans"    {"CGC"         #"^[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"	
	         "Sequence"    #"(^([A-Z0-9_cel]+)\.\d+$)|(^([A-Z0-9_cel]+)\.t\d+$)"
	         "Public_name" #"^[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^([A-Z0-9_]+)\.\d+$"}

   "briggsae"   {"CGC"         #"(^Cbr-[a-z21]{3,4}-[1-9]\d*(\.\d+)?)|(^Cbr-[a-z21]{3,4}\([a-z]+\d+\)?$)"
		 "Sequence"    #"^CBG\d{5}$"
		 "Public_name" #"(^Cbr-[a-z21]{3,4}-[1-9]\d*(\.\d+)?)|(^Cbr-[a-z21]{3,4}\([a-z]+\d+\)?$)|^CBG\d{5}$'"}

   "remanei"    {"CGC"         #"^Cre-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CRE\d{5}$"
		 "Public_name" #"^Cre-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CRE\d{5}$"}

   "brenneri"   {"CGC"         #"^Cbn-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CBN\d{5}$"
		 "Public_name" #"^Cbn-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CBN\d{5}$"}

   "pristionchus" {"CGC"         #"^Ppa-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		   "Sequence"    #"^PPA\d{5}$"
		   "Public_name" #"^Ppa-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^PPA\d{5}$"}

   "japonica"   {"CGC"         #"^Cjp-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CJA\d{5}$"
		 "Public_name" #"^Cjp-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CJA\d{5}$"}

   "brugia"     {"CGC"         #"^Bma-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^Bm\d+$"
		 "Public_name" #"^Bma-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$|^Bm\d+$"}
                 
   "ovolvulus"  {"CGC"         #"^Ovo-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
                 "Sequence"    #"OVOC\d+$"
                 "Public_name" #"^Ovo-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$|^OVOC\d+$"}})

(defn species-menu
  "Build hiccup for a species menu"
  ([name] (species-menu nil))
  ([name sel]
     [:select {:name name}
      (for [s (keys species-longnames)]
        [:option {:value s
                  :selected (if (= sel s)
                              "yes")}
         (species-longnames s)])]))

(defn nlink [id]
  [:a {:href (str "/query-gene?lookup=" id)} id])

(defn lookup-gene
  "Look up an object using either a primary of a secondary identifier."
  [db name]
  (q '[:find ?primary :in $ % ?name
       :where (has-name ?obj ?name)
              [?obj :object/name ?primary]]
     db
     '[[(has-name ?obj ?name) [?obj :object/name ?name]]
       [(has-name ?obj ?name) [?sec :object.secondary/name ?name]
                              [?obj :object/secondary ?sec]]]
     name))

(defn validate-id
  "Returns a reason why `id` is not valid in the current database, or nil if
   `id` exists and is live."
  [id]
  (let [db     (db con)
        live? (q '[:find ?live .
                     :in $ ?id
                     :where [?obj :object/name ?id]
                            [?obj :object/live ?live]]
                   db id)]
    (cond
     (nil? live?)
     "Gene does not exist"

     (not live?)
     "Gene is not live")))

(defn validate-name
  [name type species]
  (if-let [expr (get-in name-checks [species type])]
    (if-not (re-matches expr name)
      (str "Name '" name "' does not validate for " species ":" type))
    (str "Not allowed: " species ":" type)))

;;
;; New gene
;;

(defn do-new-gene [remark species new-name type]
  (if-let [err (validate-name new-name type species)]
    {:err [err]}
    (let [tid (d/tempid :db.part/user)
          tx  [[:wb/new-obj "Gene" [tid]]
               [:wb/add-name tid "Gene" type new-name]
               (txn-meta)]]
      (try
        (let [txr @(d/transact con (concat
                                    tx
                                    (update-public-name (db con) tx tid)))
              db  (:db-after txr)
              ent (touch (entity db (d/resolve-tempid db (:tempids txr) tid)))
              who (:wbperson (friend/current-authentication))
              gc  (if (= type "CGC")
                    (or (second (re-matches #"(\w{3,4})(?:[(]|-\d+)" new-name))
                        "-"))]
          (ns-email (format "WBGeneID request %s" new-name)
              "Gene"          (:object/name ent)
              "CGC_name"      (format "%s Person_evidence" new-name)
              "Public_name"   new-name
              "Species"       (species-longnames species)
              "History"       (format "Version_change 1 now %s" who)
              "Created"       "Live"
              "Gene class"    gc
              "Remark"        remark
              "Command line"  (format "newgene.pl -seq %s -who %s -load -id %s -species %s"
                                      new-name who (:object/name ent) species))
          {:done (:object/name ent)})
        (catch Exception e {:err [(.getMessage e)]})))))

(defn new-gene [{:keys [remark species new_name type]}]
  (let [type (if (= type "CGC")
               (if (authorized? #{:user.role/cgc} friend/*identity*)
                 "CGC" "Sequence")
               type)
        result (if new_name
                 (do-new-gene remark species new_name type))]
        
    (page
     (if (:done result)
       [:div.block
        [:h3 "ID generated"]
        "New gene " (nlink (:done result))
        " with name " [:strong new_name] " created."]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Request new Gene ID"]
         (for [err (:err result)]
           [:p.err err])
         
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Name type"]
           [:td
            [:select {:name "type"}
             [:option {:selected "yes"} "Sequence"]
             (if (authorized? #{:user.role/cgc} friend/*identity*)
               [:option "CGC"])]]]
          [:tr
           [:th "Name of gene"]
           [:td
            [:input {:type "text"
                     :name "new_name"
                     :size 20
                     :maxlength 40
                     :value (or new_name "")}]]]
          [:tr
           [:th "Species"]
           [:td
            (species-menu "species" species)]]
          [:tr
           [:th "Additional comment (e.g. instructions to nomenclature person)"]
           [:td
            [:input {:type "text"
                     :name "remark"
                     :size 40
                     :maxlength 200
                     :value (or remark "")}]]]]
         [:input {:type "submit"}]]]))))



;;
;; Add gene name
;;

(defn do-add-name [id type name species]
  (let
    [db   (db con)
     cid  (ffirst (lookup-gene db id))
     old-name (q '[:find ?n .
                   :in $ ?cid ?type
                   :where [?obj :object/name ?cid]
                          [?obj :object/secondary ?sec]
                          [?sec :object.secondary/name-type ?nt]
                          [?nt :name-type/name ?type]
                          [?sec :object.secondary/name ?n]]
                 db cid type)
     errs (->> [(if-not cid
                  (str "Couldn't find " id))
                (if (= type "CGC")
                  (if-not (authorized? #{:user.role/cgc} friend/*identity*)
                    "You do not have permission to add CGC names."))
                (if-not (name-checks species)
                  (str "Unknown species " species))
                (if-not (#{"CGC" "Sequence" "Public_name"} type)
                  (str "Unknown type " type))
                (if-let [existing (ffirst (lookup-gene db name))]
                  (str name " already exists as " (nlink existing)))
                (validate-name name type species)]
               (filter identity)
               (seq))]
    (if errs
      {:err errs}
      (let [txn [[:wb/ensure-max-t [:object/name cid] (d/basis-t db)]
                 [:wb/change-name [:object/name cid] "Gene" type name]
                 (txn-meta)]]
        (try
          (let [txr @(d/transact con (concat
                                      txn
                                      (update-public-name db txn [:object/name cid])))]
            (ns-email 
             (format "%s added to %s" type cid)
             "Name added" 
             (str name " " type)
             "WARNING"
             (if (and (= type "CGC")
                      old-name)
               (format "CGC name %s overwritten." old-name)))
            {:done true
             :canonical cid})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))
               
           

(defn add-gene-name [{:keys [id type name species]}]
  (page
   (let [result (if (and id name)
                  (do-add-name id type name species))]
     (if (:done result)
       [:div.block
        "Added " [:strong name] " as " type " for " (nlink (:canonical result))]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Add gene name"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "ID:"]
           [:td (ac-field "id" "Gene" id)]]
          [:tr
           [:th "Type:"]
           [:td
            [:select {:name "type"}
             [:option {:selected "yes"} "Sequence"]
             [:option "CGC"]]]]

          [:tr
           [:th "Name to add:"]
           [:td [:input {:type "text"
                         :name "name"
                         :size 20
                         :maxlength 40
                         :value (or name "")}]]]
          [:tr
           [:th "Species:"]
           [:td
            (species-menu "species" species)]]]
        
         [:input {:type "submit"}]]]))))

;;
;; Remove gene name
;;

(defn do-remove-name [id type name]
  (if (and (= type "CGC")
           (not (authorized? #{:user.role/cgc} friend/*identity*)))
    {:err ["You do not have permission to remove CGC names."]}
    (let [txn [[:wb/retract-name [:object/name id] type name]
               (txn-meta)]]
      (try
        @(d/transact con txn)
        (ns-email 
             (format "%s removed from %s" type id)
             "Name removed" 
             (str name " " type))
        {:done true}
        (catch Exception e {:err [(.getMessage (.getCause e))]})))))
  

(defn remove-gene-name [{:keys [id typename]}]
  (let [[type name] (if typename
                      (str/split typename #":"))
        result (if name
                 (do-remove-name id type name))]
    (page
     (cond 
      (:done result)
      [:div.block
       [:h3 "Remove name"]
       [:p "Removed " [:strong name] " as " type " name for gene "
           (nlink id)]]
      
      (and id (not (:err result)))
      (let [db (db con)]
        [:div.block
         [:form {:method "POST"}
          [:h3 "Remove gene name"]
          (anti-forgery-field)
          [:input {:type "hidden" :name "id" :value id}]
          [:table.info
           [:tr 
            [:th "ID:"]
            [:td [:input {:type "text"
                          :disabled "yes"
                          :name "dummy-id"
                          :value id}]]]
           [:tr
            [:th "Name:"]
            [:td [:select {:name "typename"}
                  (for [[type name] (q '[:find ?type-name ?name
                                         :in $ ?id
                                         :where [?obj :object/name ?id]
                                                [?obj :object/secondary ?sec]
                                                [?sec :object.secondary/name ?name]
                                                [?sec :object.secondary/name-type ?type]
                                                [?type :name-type/name ?type-name]]
                                       db id)]
                    [:option {:value (str type ":" name)}
                     (str name " (" type ")")])]]]]
          [:input {:type "submit"}]]])
      
      :default
      [:div.block
       [:form {:method "POST"}
        [:h3 "Remove gene name"]
        (for [e (:err result)]
          [:p.err e])
        (anti-forgery-field)
        [:table.info
         [:tr 
          [:th "ID:"]
          [:td (ac-field "id" "Gene" id)]]]
        [:input {:type "submit"}]]]))))

;;
;; Merge two genes
;;

(defn cgc-name [db id]
  (q '[:find ?name .
       :in $ ?id
       :where [?obj :object/name ?id]
              [?obj :object/secondary ?sec]
              [?cgc :name-type/name "CGC"]
              [?sec :object.secondary/name-type ?cgc]
              [?sec :object.secondary/name ?name]]
     db id))
     
(defn do-merge-genes [id idx]
  (let [db   (db con)
        cid  (ffirst (lookup-gene db id))
        cidx (ffirst (lookup-gene db idx))
        obj  (entity db [:object/name cid])
        objx (entity db [:object/name cidx])
        is-cgc? (authorized? #{:user.role/cgc} friend/*identity*)
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
                          " into itself"))
                   (if-not is-cgc?
                     (if-let [cgcx (cgc-name db cidx)]
                       (if-let [cgc (cgc-name db cid)]
                         (str "Both genes have cgc names " cgc " and " cgcx ". "
                              "please contact the Geneace curator.")
                         (str "Gene to be killed has a cgc name " cgcx "."
                              "please contact the Geneace curator."))))]
                   (filter identity)
                   (seq))]
    (if errs
      {:err errs}
      (let [txn [[:wb/ensure-max-t [:object/name cid]  (d/basis-t db)]
                 [:wb/ensure-max-t [:object/name cidx] (d/basis-t db)]
                 [:wb/merge [:object/name cid] [:object/name cidx]]]]
        (try
          (let [txr @(d/transact con txn)]
            (ns-email (format "Merged genes %s (%s) - %s (%s)" id cid idx cidx)
                "LIVE" (format "retained gene %s" cid)
                "DEAD" (format "killed   gene %s" cidx))
            {:done true})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn merge-gene [{:keys [id idx]}]
  (page
   (let [result (if (and id idx)
                  (do-merge-genes id idx))]
     (if (:done result)
       [:div.block
        "Merged genes"]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Merge genes"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Gene to stay alive:"]
           [:td (ac-field "id" "Gene" idx)]]
          [:tr
           [:th "Gene to remove:"]
           [:td (ac-field "idx" "Gene" idx)]]]
         [:input {:type "submit"}]]]))))

;;
;; Split a gene
;;

(defn do-split-gene [id name species reason type]
  (let [db  (db con)
        cid (ffirst (lookup-gene db id))
        err (->> [(if-not cid
                    (str "No match for " id))
                  (validate-name name type species)]
                 (filter identity)
                 (seq))]
    (if err
      {:err err}
      (let [temp (d/tempid :db.part/user)
            txn  [[:wb/ensure-max-t [:object/name cid] (d/basis-t db)]
                  [:wb/split [:object/name cid] temp]
                  [:wb/add-name temp "Gene" type name]
                  (txn-meta)]]
        (try 
          (let [txr @(d/transact con (concat
                                      txn
                                      (update-public-name db txn temp)))
                db (:db-after txr)
                ent (entity db (d/resolve-tempid db (:tempids txr) temp))]
            (ns-email (format "Split gene %s" cid)
                "ID"             cid
                "New ID"         (:object/name ent)
                "New CDS"        name
                "Command_line"   (format "splitgene.pl -old %s -new %s -who %s -id %s -load -species %s"
                                         cid
                                         name
                                         (:wbperson (friend/current-authentication))
                                         (:object/name ent)
                                         species)
                "Remark"         reason)
            {:done      (:object/name ent)
             :canonical cid})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn split-gene [{:keys [id name species reason type] :or {type "Sequence"}}]
  (let [status (if (and id name species)
                 (do-split-gene id name species reason type))]
    (page
     (if (:done status)
       [:div.block
        [:h3 "Split gene"]
        "Split gene " (nlink id)
        " creating " (nlink (:done status))
        " with sequence name " [:strong name]]
        
       [:div.block
        [:form {:method "POST"}
         [:h3 "Split genes"]
         (for [err (:err status)]
           [:p.err err])
           
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Gene to split:"]
           [:td (ac-field "id" "Gene" id)]]
          [:tr
           [:th "Sequence name of new gene:"]
           [:td
            [:input {:type "text"
                     :name "name"
                     :size 20
                     :maxlength 40
                     :value (or name "")}]]]
          [:tr
           [:th "Species:"]
           [:td
            (species-menu "species" species)]]
          
          [:tr
           [:th "Reason:"]
           [:td
            [:input {:type "text"
                     :name "reason"
                     :size 40
                     :maxlength 200
                     :value (or reason "")}]]]]
         [:p "If you're making a pseudogene, just create it as a new Sequence gene"]
         [:input {:type "submit"}]]]))))

;;
;;
;;

(def ^:private class-change-codes
  {"Pseudogene"      "CP"
   "coding"          "PC"
   "Transposon_CDS"  "CT"
   "Transcript"      "CR"})

(defn do-change-class [cds class species]
  (let [db     (db con)
        cid    (ffirst (lookup-gene db cds))
        cp     (class-change-codes class)
        who    (:wbperson (friend/current-authentication))]
    (if cid
      (if-let [seq-name (q '[:find ?sn .
                             :in $ ?id
                             :where [?obj :object/name ?id]
                                    [?obj :object/secondary ?sec]
                                    [?nt  :name-type/name "Sequence"]
                                    [?sec :object.secondary/name-type ?nt]
                                    [?sec :object.secondary/name ?sn]]
                           db cid)]
        (if (= class "Transposon_CDS")
          (try
            @(d/transact con [[:wb/ensure-max-t [:object/name cid] (d/basis-t db)]
                              [:wb/kill [:object/name cid]]
                              (txn-meta)])
            (ns-email (format "Changing %s to %s" cid class)
                "Command line" (format "changegene.pl -seq %s -species %s -who %s -load -class %s"
                                       seq-name species who cp)
                "Note" "Remember you need to add a 2-letter change code after -class"
                "WARNING" (format "Killed %s in the nameserver" cid))
            {:done true}
            (catch Exception e {:err [(.getMessage e)]}))
          (do
            (ns-email
             (format "Changing %s to %s" cid class)
             "Command line" (format "changegene.pl -seq %s -species %s -who %s -load -class %s"
                                    seq-name species who cp)
             "Note" "Remember you need to add a 2-letter change code after -class"
             "WARNING" "This does not affect nameserver db")
            {:done true}))
        {:err [(format "Couldn't get a Sequence name for %s" cds)]})
      {:err [(format "Couldn't find %s" cds)]})))

(defn change-gene-class [{:keys [cds class species]}]
  (let [result (if cds
                 (do-change-class cds class species))]
    (page
     (if (:done result)
       [:div.block "Changed " [:strong cds] " to " [:strong class] "."]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Change gene class"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Gene name"]
           [:td (ac-field "cds" "Gene" cds)]]
          [:tr
           [:th "Change to..."]
           [:td
            [:select {:name "class"}
             (for [s ["coding" "Pseudogene" "Transcript" "Transposon_CDS"]]
               [:option {:selected (if (= s class) "yes")} s])]]]

          [:tr
           [:th "Species:"]
           [:td
            (species-menu "species" species)]]]

         [:input {:type "submit"}]]]))))
         
;;
;; Dump all gene names
;;

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
              (doseq [[oid] (q '[:find ?oid
                                 :where [?dom :domain/name "Gene"]
                                 [?oid :object/domain ?dom]]
                               db)
                      :let [ent (entity db oid)
                            names (->> (for [s (:object/secondary ent)]
                                         [(:name-type/name (:object.secondary/name-type s))
                                          (:object.secondary/name s)])
                                       (into {}))]]
                (println
                 (str/join
                  "\t"
                  [(:object/name ent)
                   (get names "CGC" "-")
                   (get names "Sequence" "-")
                   (if (:object/live ent)
                     "live"            
                     "DEAD")]))))
            (.flush pw))))}
      (page 
       [:div.block
        [:form {:method "POST"}
         [:h3 "Dump Gene IDs"]
         (anti-forgery-field)
         [:p "Are you sure you want to dump all IDs?  This can take a few seconds."]
         [:input {:type "hidden"
                  :name "confirm"
                  :value "yes"}]
         [:input {:type "submit"}]]]))))

