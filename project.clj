(defproject url-shortener "0.1.0-SNAPSHOT"
  :description "Production-grade URL shortener in Clojure with Diplomat Architecture, Datomic and Kafka."
  :url "https://github.com/Ryanditko/clj-url-shortener"
  :license {:name "Academic"}

  :dependencies [[org.clojure/clojure              "1.12.0"]

                 ;; HTTP - Pedestal + Ring
                 [io.pedestal/pedestal.service      "0.7.2"]
                 [io.pedestal/pedestal.jetty        "0.7.2"]
                 [ring/ring-core                    "1.13.0"]

                 ;; Datomic
                 [com.datomic/peer                  "1.0.7260"]

                 ;; Kafka
                 [org.apache.kafka/kafka-clients    "3.9.0"]

                 ;; Schema validation
                 [prismatic/schema                  "1.4.1"]

                 ;; Utilities
                 [org.clojure/tools.logging         "1.3.0"]
                 [ch.qos.logback/logback-classic    "1.5.16"]
                 [org.clojure/data.json             "2.5.1"]
                 [aero/aero                         "1.1.6"]]

  :source-paths      ["src"]
  :resource-paths    ["resources"]
  :test-paths        ["test"]

  :main url-shortener.core
  :aot  [url-shortener.core]

  :profiles {:dev  {:dependencies [[org.clojure/test.check "1.1.1"]]
                    :source-paths ["dev"]}
             :test {:dependencies [[org.clojure/test.check "1.1.1"]]}}

  :aliases {"migrate" ["run" "-m" "url-shortener.diplomat.datomic.migrate"]})
