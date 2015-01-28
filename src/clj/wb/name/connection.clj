(ns wb.name.connection
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [environ.core           :refer [env]]))

(def uri (env :wb-namedb-uri))
(def con (d/connect uri))
