(defproject wormnames "0.0.2-SNAPSHOT"
  :description "Next-gen Wormbase Nameserver"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.datomic/datomic-free "0.9.5130"]
                 [datomic-schema "1.1.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [hiccup "1.0.5"]
                 [ring "1.3.2"]
                 [compojure "1.3.1"]
                 [com.cemerick/friend "0.2.1"]
                 [ring/ring-anti-forgery "1.0.0"]
                 [friend-oauth2 "0.1.3"]
                 [environ "0.5.0"]
                 [org.apache.httpcomponents/httpclient "4.3.6"]
                 [base64-clj "0.1.1"]
                 [cheshire "5.4.0"]
                 [com.draines/postal "1.11.3"]
                 [clj-time "0.9.0"]

                 ;; For importer only.  Should be dev dependencies
                 ;; or something?
                 [org.clojure/java.jdbc "0.3.5"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [mysql/mysql-connector-java "5.1.6"]]
  :source-paths ["src/clj"]

  :profiles {:dev {:dependencies [[alembic "0.3.2"]]
                   :plugins [[lein-environ "0.5.0"]]}}
  
  :jvm-opts ["-Xmx4G" "-Ddatomic.txTimeoutMsec=5000"])
