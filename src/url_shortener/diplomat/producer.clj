(ns url-shortener.diplomat.producer
  (:require [schema.core :as s]
            [com.stuartsierra.component :as component]
            [url-shortener.wire.out.kafka-event :as wire.kafka]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json])
  (:import [org.apache.kafka.clients.producer KafkaProducer ProducerRecord ProducerConfig]
           [org.apache.kafka.common.serialization StringSerializer]))

(defrecord Producer [config producer]
  component/Lifecycle
  
  (start [this]
    (if producer
      this
      (try
        (let [props (doto (java.util.Properties.)
                      (.put ProducerConfig/BOOTSTRAP_SERVERS_CONFIG 
                            (:kafka-bootstrap-servers config "localhost:9092"))
                      (.put ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG 
                            (.getName StringSerializer))
                      (.put ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG 
                            (.getName StringSerializer))
                      (.put ProducerConfig/ACKS_CONFIG "all")
                      (.put ProducerConfig/RETRIES_CONFIG (int 3))
                      (.put ProducerConfig/LINGER_MS_CONFIG (int 1))
                      (.put ProducerConfig/MAX_BLOCK_MS_CONFIG (int 5000)))
              kafka-producer (KafkaProducer. props)]
          (log/info "Kafka producer started")
          (assoc this :producer kafka-producer))
        (catch Exception e
          (log/warn "Kafka producer failed to start, events will be dropped" 
                    {:error (.getMessage e)})
          (assoc this :producer nil)))))
  
  (stop [this]
    (when producer
      (try (.close producer) (catch Exception _)))
    (assoc this :producer nil)))

(defn new-producer [config]
  (map->Producer {:config config}))

(defn publish-event! [producer topic event]
  (when-let [kafka-producer (:producer producer)]
    (try
      (let [key (:event-id event)
            value (json/write-str event)
            record (ProducerRecord. topic key value)]
        @(.send kafka-producer record))
      (catch Exception e
        (log/warn "Failed to publish event, skipping" {:topic topic :error (.getMessage e)})))))

(defn publish-url-created! [producer event]
  (publish-event! producer "url-shortener.url.created" event))

(defn publish-url-accessed! [producer event]
  (publish-event! producer "url-shortener.url.accessed" event))

(defn publish-url-deactivated! [producer event]
  (publish-event! producer "url-shortener.url.deactivated" event))
