(ns wb.name.web
  (:gen-class)
  (:use hiccup.core
        ring.middleware.stacktrace 
        ring.middleware.params 
        ring.middleware.session 
        ring.middleware.anti-forgery
        ring.middleware.cookies
        ring.util.anti-forgery
        wb.edn
        wb.name.utils)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [ring.adapter.jetty :refer (run-jetty)]
            [compojure.core :refer (defroutes GET POST)]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.util.response :refer [file-response]]
            [cemerick.friend :as friend :refer [authorized?]]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])
            [friend-oauth2.workflow :as oauth2]
            [friend-oauth2.util     :refer [format-config-uri]]
            [environ.core           :refer [env]]
            [cheshire.core :as json :refer [parse-string]]
            [base64-clj.core :as base64]
            [wb.name.connection :refer [con]]
            [wb.name.web.common :as common]
            [wb.name.web.gene :as gene]
            [wb.name.web.variation :as vari]
            [wb.name.web.feature :as feature]
            [wb.name.web.bits :refer [txn-meta]]
            [wb.name.ssl :as ssl]
            [clojure.tools.nrepl.server :as nrepl]))

(defn- lookup-primary [db domain-id prefix]
  (->> (d/seek-datoms db :avet :object/name prefix)
       (take-while #(= (q '[:find ?dom .
                            :in $ ?obj
                            :where [?obj :object/domain ?dom]]
                          db (:e %))
                       domain-id))
       (take-while #(.startsWith (:v %) prefix))
       (map :v)))

(defn- lookup-secondary [db domain-id prefix]
  (let [dom-name-types (set
                        (q '[:find [?nt ...]
                             :in $ ?dom
                             :where [?nt :name-type/domain ?dom]]
                           db domain-id))]
    (->> (d/seek-datoms db :avet :object.secondary/name prefix)
         (take-while #(dom-name-types
                       (q '[:find ?nt .
                            :in $ ?obj
                            :where [?obj :object.secondary/name-type ?nt]]
                          db (:e %))))
         (take-while #(.startsWith (:v %) prefix))
         (map :v))))

(defn api-lookup [{:keys [domain prefix]}]
  (let [db (db con)
        domain-id (:db/id (entity db [:domain/name domain]))]
   (json/generate-string
    (take 10
     (lazy-cat
      (lookup-primary db domain-id prefix)
      (lookup-secondary db domain-id prefix))))))

(defn api-query [{:keys [query params]}]
  (pr-str (apply q query (db con) params)))

(defn- query-tempid-names [{db :db-after tempids :tempids}]
  (q '[:find ?name
       :in $ [?obj ...]
       :where [?obj :object/name ?name]]
     db (for [[t i] tempids] i)))

(defn api-transact [{:keys [transaction tempid-report]}]
  (let [errs (->> (for [tx-cmd transaction]
                    (cond
                     (not (vector? tx-cmd))
                     (str "Only vector transation elements are supported by the name server: " tx-cmd)
                     
                     (not= (namespace (first tx-cmd)) "wb")
                     (str "Name server transactions must invoke functions in the :wb namespace:" tx-cmd)))
                  (filter identity)
                  (seq))]
    (if errs
      (pr-str {:success false
               :error (first errs)})
      (try
        (let [txr @(d/transact con (conj transaction (txn-meta)))]
          (pr-str
           (vmap
            :success true
            :tempid-report (if tempid-report
                             (query-tempid-names txr)))))
        (catch Exception e (pr-str {:success false :error (.getMessage e)}))))))
    

(defroutes app-routes
  (GET "/query-gene" {params :params} (common/query "Gene" params))

  (GET "/new-gene" [] (friend/authorize #{:user.role/edit} (gene/new-gene {})))
  (POST "/new-gene" {params :params} (friend/authorize #{:user.role/edit} (gene/new-gene params)))

  (GET "/kill-gene" []
       (friend/authorize #{:user.role/edit} (common/kill-object "Gene" {})))
  (POST "/kill-gene" {params :params}
        (friend/authorize #{:user.role/edit} (common/kill-object "Gene" params)))

  (GET "/merge-gene" []
       (friend/authorize #{:user.role/edit} (gene/merge-gene {})))
  (POST "/merge-gene" {params :params}
        (friend/authorize #{:user.role/edit} (gene/merge-gene params)))

  (GET "/split-gene" []
       (friend/authorize #{:user.role/edit} (gene/split-gene {})))
  (POST "/split-gene" {params :params}
        (friend/authorize #{:user.role/edit} (gene/split-gene params)))
  
  (GET "/add-gene-name" []
       (friend/authorize #{:user.role/edit} (gene/add-gene-name {})))
  (POST "/add-gene-name" {params :params}
        (friend/authorize #{:user.role/edit} (gene/add-gene-name params)))

  (GET "/remove-gene-name" []
       (friend/authorize #{:user.role/edit} (gene/remove-gene-name {})))
  (POST "/remove-gene-name" {params :params}
        (friend/authorize #{:user.role/edit} (gene/remove-gene-name params)))

  (GET "/change-gene-class" []
       (friend/authorize #{:user.role/edit} (gene/change-gene-class {})))
  (POST "/change-gene-class" {params :params}
        (friend/authorize #{:user.role/edit} (gene/change-gene-class params)))

  (GET "/dump-genes" []
       (friend/authorize #{:user.role/view} (gene/dump {})))
  (POST "/dump-genes" {params :params}
        (friend/authorize #{:user.role/view} (gene/dump params)))



  
  (GET "/query-variation" {params :params}
       (friend/authorize #{:user.role/view} (common/query "Variation" params)))

  (GET "/variation-last-id" []
       (friend/authorize #{:user.role/view} (common/last-id "Variation")))

  (GET "/variation-change-name" []
       (friend/authorize #{:user.role/edit} (vari/change-name {})))
  (POST "/variation-change-name" {params :params}
        (friend/authorize #{:user.role/edit} (vari/change-name params)))

  (GET "/variation-request-id" []
       (friend/authorize #{:user.role/edit} (vari/new {})))
  (POST "/variation-request-id" {params :params}
        (friend/authorize #{:user.role/edit} (vari/new params)))

  (GET "/variation-kill" []
       (friend/authorize #{:user.role/edit} (common/kill-object "Variation" {})))
  (POST "/variation-kill" {params :params}
        (friend/authorize #{:user.role/edit} (common/kill-object "Variation" params)))

  (GET "/variation-merge" []
       (friend/authorize #{:user.role/edit} (common/merge-objects "Variation" {})))
  (POST "/variation-merge" {params :params}
        (friend/authorize #{:user.role/edit} (common/merge-objects "Variation" params)))

  (GET "/dump-variations" []
       (friend/authorize #{:user.role/edit} (vari/dump {})))
  (POST "/dump-variations" {params :params}
        (friend/authorize #{:user.role/edit} (vari/dump params)))


  
  (GET "/query-feature" {params :params}
       (friend/authorize #{:user.role/view} (common/query "Feature" params)))

  (GET "/feature-new" []
       (friend/authorize #{:user.role/edit} (feature/new {})))
  (POST "/feature-new" {params :params}
        (friend/authorize #{:user.role/edit} (feature/new params)))

  (GET "/feature-kill" []
       (friend/authorize #{:user.role/edit} (common/kill-object "Feature" {})))
  (POST "/feature-kill" {params :params}
        (friend/authorize #{:user.role/edit} (common/kill-object "Feature" params)))

  (GET "/feature-merge" []
       (friend/authorize #{:user.role/edit} (common/merge-objects "Feature" {})))
  (POST "/feature-merge" {params :params}
        (friend/authorize #{:user.role/edit} (common/merge-objects "Feature" params)))

  )
  

(defroutes api-routes
  (GET "/api/lookup" {params :params}
       (friend/authorize #{:user.role/view} (api-lookup params)))

  (POST "/api/query" {params :params}
        (friend/authorize #{:user.role/query}) (api-query params))
  (POST "/api/transact" {params :params}
        (friend/authorize #{:user.role/transact}) (api-transact params))

  (GET "/test" req (str "hello " (:wbperson (friend/current-authentication)))))

(defroutes routes
  api-routes
  (wrap-anti-forgery app-routes)
  (route/files "/" {:root "resources/public"}))

(defn- goog-credential-fn [token]
  (if-let [u (entity (db con) [:user/email (:id (:access-token token))])]
    {:identity token
     :email (:user/email u)
     :wbperson (:user/wbperson u)
     :roles (:user/role u)}
    {:identity token
     :wbperson "unauthorized"
     :roles #{:user.role/none}}))

(defn- ssl-credential-fn [{:keys [ssl-client-cert]}]
  (if-let [u (entity (db con) [:user/x500-cn (->> (.getSubjectX500Principal ssl-client-cert)
                                                  (.getName)
                                                  (re-find #"CN=([^,]+)")
                                                  (second))])]
    {:identity ssl-client-cert
     :wbperson (:user/wbperson u)
     :roles (:user/role u)}))

(def client-config {:client-id      (env :wb-oauth2-client-id)
                    :client-secret  (env :wb-oauth2-client-secret)
                    :callback {:domain (or (env :wb-oauth2-redirect-domain)
                                           "http://127.0.0.1:8130")
                               :path "/oauth2callback"}})
                    

(def uri-config
  {:authentication-uri {:url "https://accounts.google.com/o/oauth2/auth"
                        :query {:client_id (:client-id client-config)
                               :response_type "code"
                               :redirect_uri (format-config-uri client-config)
                               :scope "email"}}

   :access-token-uri {:url "https://accounts.google.com/o/oauth2/token"
                      :query {:client_id (:client-id client-config)
                              :client_secret (:client-secret client-config)
                              :grant_type "authorization_code"
                              :redirect_uri (format-config-uri client-config)}}})

(defn- flex-decode [s]
  (let [m (mod (count s) 4)
        s (if (> m 0)
            (str s (.substring "====" m))
            s)]
    (base64/decode s)))
    

(defn- goog-token-parse [resp]
  (let [token     (parse-string (:body resp) true)
        id-token  (parse-string (flex-decode (second (str/split (:id_token token) #"\."))) true)]
    {:access_token (:access_token token)
     :id (:email id-token)}))

(def app
  (handler/site
  (-> routes
      (friend/authenticate {:allow-anon? false
                            :workflows [(ssl/client-cert-workflow
                                         :credential-fn ssl-credential-fn)
                                        (oauth2/workflow
                                         {:client-config client-config
                                          :uri-config uri-config
                                          :access-token-parsefn goog-token-parse
                                          :credential-fn goog-credential-fn})]})
      wrap-edn-params
      wrap-params
      wrap-stacktrace
      wrap-session
      wrap-cookies)))

(def keystore (env :wb-ssl-keystore ))
(def keypass (env :wb-ssl-password))
(def http-port (if-let [x (env :wb-http-port)]
                 (Integer/parseInt x)
                 8130))
(def https-port (if-let [x (env :wb-https-port)]
                  (Integer/parseInt x)
                  8131))

(println "HTTP port " http-port)
(println "HTTPS port" https-port)

(defonce server 
  (when-not *compile-files*
    (do
      (println "Running server")
    (run-jetty #'app (if keystore
                       {:port http-port
                        :join? false
                        :ssl-port https-port
                        :keystore keystore
                        :key-password keypass
                        :truststore keystore
                        :trust-password keypass
                        :client-auth :want}
                       {:port http-port
                        :join? false})))))

(defn -main
  "Dummy entry point"
  [& args]
  (println "Name server running")
  (if-let [repl-port (env :wb-nrepl-port)]
    (nrepl/start-server :port (Integer/parseInt repl-port))))
          
