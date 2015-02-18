(ns wb.name.mail
  (:require [wb.name.connection :refer [con]]
            [datomic.api :as d :refer [q db touch entity]]
            [postal.core :as p]
            [cemerick.friend :as friend :refer [authorized?]]
            [environ.core :refer [env]]))

(defn- format-ns-email [auth details]
  (with-out-str
    (println)
    (println "          EMAIL :" (:email auth))
    (println "           NAME :" (:wbperson auth))
    (println)
    (doseq [[key val] (partition 2 details)]
      (if-not (nil? val)
        (printf "%15s : %s%n" key val)))))

(defn ns-email
  "Attempt to send a name-server email with subject `subject` and a body
   containing `details` interpretted as a sequence of key-value pairs.
   Detail elements with nil values are omitted"
  [subject & details]
  (let [body   (format-ns-email (friend/current-authentication) 
                                details)
        recpts (q '[:find [?e ...]
                    :where [?u :user/role :user.role/receive-email]
                           [?u :user/email ?e]]
                  (db con))
        from (env :wb-email-from)
        tag (or (env :wb-email-tag)
                "[TEST NAMESERVER] ")]
    (if (and from (seq recpts))
      (future
        (p/send-message 
         
         {:from from
          :to (seq recpts)
          :subject (str tag (or subject "Name server"))
          :body body
          }))
      (do
        (println "Couldn't send email")
        (println "From:" from)
        (println "To:" recpts)
        (println "Subject:" subject)
        (println body)))))
    
