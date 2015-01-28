(ns wb.name.web
  (:use hiccup.core
        ring.middleware.stacktrace 
        ring.middleware.params 
        ring.middleware.session 
        ring.middleware.anti-forgery
        ring.middleware.cookies
        ring.util.anti-forgery
        wb.edn)
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
            [wb.name.web.bits :refer [txn-meta]]
            [wb.name.ssl :as ssl]))

(defn api-lookup [{:keys [prefix]}]
  (let [db (db con)]
   (json/generate-string
    (concat
     (->> (d/seek-datoms db :avet :object/name prefix)
          (take 10)
          (map #(nth % 2))
          (filter #(.startsWith % prefix)))
     (->> (d/seek-datoms db :avet :object.secondary/name prefix)
          (take 10)
          (map #(nth % 2))
          (filter #(.startsWith % prefix)))))))

(defn api-query [{:keys [query params]}]
  (pr-str (apply q query (db con) params)))

(defn api-transact [{:keys [transaction]}]
  (try
    @(d/transact con (conj transaction (txn-meta)))
    (pr-str {:success true})
    (catch Exception e (pr-str {:success false :error (.getMessage e)}))))


(defroutes app-routes
  (GET "/query-gene" {params :params} (gene/get-query-gene params))

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
       (friend/authorize #{:user.role/view} (vari/query params)))

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
       (friend/authorize #{:user.role/edit} (vari/merge {})))
  (POST "/variation-merge" {params :params}
        (friend/authorize #{:user.role/edit} (vari/merge params)))

  (GET "/dump-variations" []
       (friend/authorize #{:user.role/edit} "Dump"))
  (POST "/dump-variations" {params :params}
        (friend/authorize #{:user.role/edit} "Dump")))
  

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

(defn credential-fn [token]
  (if-let [u (entity (db con) [:user/email (:id (:access-token token))])]
    {:identity token
     :email (:user/email u)
     :wbperson (:user/wbperson u)
     :roles (:user/role u)}
    {:identity token
     :wbperson "unauthorized"
     :roles #{:user.role/none}}))

(defn ssl-credential-fn [{:keys [ssl-client-cert]}]
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

(defn flex-decode [s]
  (let [m (mod (count s) 4)
        s (if (> m 0)
            (str s (.substring "====" m))
            s)]
    (base64/decode s)))
    

(defn my-token-parse [resp]
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
                                          :access-token-parsefn my-token-parse
                                          :credential-fn credential-fn})]})
      wrap-edn-params
      wrap-params
      wrap-stacktrace
      wrap-session
      wrap-cookies)))

(def keystore (env :wb-ssl-keystore ))
(def keypass (env :wb-ssl-password))

(defonce server (run-jetty #'app (if keystore
                                   {:port 8130
                                    :join? false
                                    :ssl-port 8131
                                    :keystore keystore
                                    :key-password keypass
                                    :truststore keystore
                                    :trust-password keypass
                                    :client-auth :want}
                                   {:port 8130
                                    :join? false})))