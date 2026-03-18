(ns url-shortener.diplomat.cache
  (:require [taoensso.carmine :as car]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]))

(defrecord Cache [config conn-spec]
  component/Lifecycle
  
  (start [this]
    (if conn-spec
      this
      (let [spec {:pool {}
                  :spec {:host (:redis-host config "localhost")
                         :port (:redis-port config 6379)
                         :timeout-ms 3000}}]
        (log/info "Redis cache configured" {:host (:redis-host config)})
        (assoc this :conn-spec spec))))
  
  (stop [this]
    (assoc this :conn-spec nil)))

(defn new-cache [config]
  (map->Cache {:config config}))

(defmacro wcar* [conn-spec & body]
  `(car/wcar ~conn-spec ~@body))

(defn set-url! [cache cached-url ttl]
  (try
    (let [key (str "url:short-code:" (:short-code cached-url))
          value (json/write-str cached-url)]
      (wcar* (:conn-spec cache) (car/setex key ttl value)))
    (catch Exception e
      (log/warn "Cache set-url! failed, skipping" {:error (.getMessage e)}))))

(defn get-url [cache short-code]
  (try
    (let [result (wcar* (:conn-spec cache) (car/get (str "url:short-code:" short-code)))]
      (when (and result (string? result))
        (json/read-str result :key-fn keyword)))
    (catch Exception _
      nil)))

(defn delete-url! [cache short-code]
  (try
    (wcar* (:conn-spec cache) (car/del (str "url:short-code:" short-code)))
    (catch Exception e
      (log/warn "Cache delete-url! failed, skipping" {:error (.getMessage e)}))))

(defn set-stats! [cache stats ttl]
  (try
    (let [key (str "stats:" (:short-code stats))
          value (json/write-str stats)]
      (wcar* (:conn-spec cache) (car/setex key ttl value)))
    (catch Exception e
      (log/warn "Cache set-stats! failed, skipping" {:error (.getMessage e)}))))

(defn get-stats [cache short-code]
  (try
    (let [result (wcar* (:conn-spec cache) (car/get (str "stats:" short-code)))]
      (when (and result (string? result))
        (json/read-str result :key-fn keyword)))
    (catch Exception _
      nil)))
