(ns url-shortener.diplomat.consumer
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [url-shortener.diplomat.datomic :as diplomat.datomic])
  (:import [org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig]
           [org.apache.kafka.common.serialization StringDeserializer]
           [java.time Duration Instant ZoneOffset]
           [java.util Date]))

(defn- parse-event [record]
  (try
    (json/read-str (.value record) :key-fn keyword)
    (catch Exception e
      (log/warn "Failed to parse event" {:error (.getMessage e)})
      nil)))

(defn- date->day-start [^Date date]
  (let [local-date (-> (.toInstant date) (.atZone ZoneOffset/UTC) .toLocalDate)
        instant (.toInstant (.atStartOfDay local-date ZoneOffset/UTC))]
    (Date/from instant)))

(defn- process-accessed-event [datomic event]
  (try
    (let [short-code (:short-code event)
          timestamp-str (:timestamp event)
          timestamp (Date/from (Instant/parse timestamp-str))
          day-start (date->day-start timestamp)
          ip-address (:ip-address event)
          existing (diplomat.datomic/find-daily-analytics datomic short-code day-start)
          today-analytics (first (filter #(= (:analytics/date %) day-start) existing))]
      (if today-analytics
        (diplomat.datomic/save-daily-analytics!
         datomic
         {:analytics/short-code short-code
          :analytics/date day-start
          :analytics/clicks (inc (:analytics/clicks today-analytics 0))
          :analytics/unique-visitors (:analytics/unique-visitors today-analytics 0)})
        (diplomat.datomic/save-daily-analytics!
         datomic
         {:analytics/short-code short-code
          :analytics/date day-start
          :analytics/clicks 1
          :analytics/unique-visitors (if ip-address 1 0)})))
    (catch Exception e
      (log/warn "Failed to process accessed event" {:error (.getMessage e)}))))

(defn- poll-loop [consumer datomic running?]
  (log/info "Kafka consumer poll loop started")
  (try
    (while @running?
      (let [records (.poll consumer (Duration/ofMillis 1000))]
        (doseq [record records]
          (when-let [event (parse-event record)]
            (case (:event-type event)
              "url.accessed" (process-accessed-event datomic event)
              (log/debug "Ignoring event type" {:type (:event-type event)}))))))
    (catch org.apache.kafka.common.errors.WakeupException _
      (log/info "Consumer wakeup received, shutting down"))
    (catch Exception e
      (log/error e "Consumer poll loop error"))
    (finally
      (log/info "Kafka consumer poll loop stopped"))))

(defrecord Consumer [config datomic consumer running? poll-thread]
  component/Lifecycle

  (start [this]
    (if consumer
      this
      (try
        (let [props (doto (java.util.Properties.)
                      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG
                            (:kafka-bootstrap-servers config "localhost:9092"))
                      (.put ConsumerConfig/GROUP_ID_CONFIG
                            (:consumer-group-id config "url-shortener-analytics"))
                      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG
                            (.getName StringDeserializer))
                      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG
                            (.getName StringDeserializer))
                      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest")
                      (.put ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG "true")
                      (.put ConsumerConfig/MAX_POLL_INTERVAL_MS_CONFIG (int 300000)))
              kafka-consumer (KafkaConsumer. props)
              running-flag (atom true)]
          (.subscribe kafka-consumer ["url-shortener.url.accessed"])
          (let [thread (Thread. #(poll-loop kafka-consumer datomic running-flag)
                                "kafka-consumer-poll")]
            (.setDaemon thread true)
            (.start thread)
            (log/info "Kafka consumer started")
            (assoc this
                   :consumer kafka-consumer
                   :running? running-flag
                   :poll-thread thread)))
        (catch Exception e
          (log/warn "Kafka consumer failed to start, analytics will not be processed"
                    {:error (.getMessage e)})
          (assoc this :consumer nil)))))

  (stop [this]
    (when running?
      (reset! running? false))
    (when consumer
      (try
        (.wakeup consumer)
        (when poll-thread
          (.join poll-thread 5000))
        (.close consumer)
        (catch Exception _)))
    (assoc this :consumer nil :running? nil :poll-thread nil)))

(defn new-consumer [config]
  (map->Consumer {:config config}))
