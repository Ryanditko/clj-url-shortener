(ns url-shortener.system
  (:require [com.stuartsierra.component :as component]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.cache :as diplomat.cache]
            [url-shortener.diplomat.producer :as diplomat.producer]
            [url-shortener.diplomat.http-server :as diplomat.http-server]))

(defn create-system [config]
  (component/system-map
   :datomic (diplomat.datomic/new-datomic config)
   :cache (diplomat.cache/new-cache config)
   :producer (diplomat.producer/new-producer config)
   :http-server (component/using
                 (diplomat.http-server/new-http-server config)
                 [:datomic :cache :producer])))

(defn start-system! [config]
  (component/start (create-system config)))

(defn stop-system! [system]
  (component/stop system))
