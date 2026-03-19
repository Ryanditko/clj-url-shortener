(ns url-shortener.core
  (:gen-class)
  (:require [url-shortener.system :as system]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn load-config
  ([] (load-config (keyword (or (System/getenv "APP_PROFILE") "dev"))))
  ([profile] (aero/read-config (io/resource "config.edn") {:profile profile})))

(defn -main [& _args]
  (let [config (load-config)]
    (log/info "Starting URL Shortener service")
    (let [system (system/start-system! config)]
      (log/info "Service started successfully")
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (log/info "Shutting down service")
                  (system/stop-system! system)
                  (log/info "Service stopped"))))
      @(promise))))
