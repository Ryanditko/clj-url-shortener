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

                 ;; Redis Cache
                 [com.taoensso/carmine              "3.4.1"]

                 ;; Dependency Injection
                 [com.stuartsierra/component        "1.1.0"]

                 ;; Schema validation
                 [prismatic/schema                  "1.4.1"]

                 ;; Authentication
                 [buddy/buddy-sign                  "3.6.1-359"]
                 [buddy/buddy-hashers               "2.0.167"]

                 ;; Observability - Prometheus
                 [io.prometheus/prometheus-metrics-core                "1.5.0"]
                 [io.prometheus/prometheus-metrics-model               "1.5.0"]
                 [io.prometheus/prometheus-metrics-instrumentation-jvm "1.5.0"]
                 [io.prometheus/prometheus-metrics-exposition-formats  "1.5.0"]

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

  :profiles {:dev  {:dependencies [[org.clojure/test.check "1.1.1"]
                                   [io.pedestal/pedestal.service-tools "0.7.2"]]
                    :source-paths ["dev"]}
             :test {:dependencies [[org.clojure/test.check "1.1.1"]
                                   [io.pedestal/pedestal.service-tools "0.7.2"]]}}

  :plugins [[lein-cloverage "1.2.4"]
            [lein-ancient "0.7.0"]]

  :aliases {"migrate" ["run" "-m" "url-shortener.diplomat.datomic.migrate"]
            "test-unit" ["test" ":only" 
                         "url-shortener.logic.shortener-test"
                         "url-shortener.logic.auth-test"
                         "url-shortener.logic.rate-limiter-test"
                         "url-shortener.adapters.url-test"
                         "url-shortener.controllers.url-test"
                         "url-shortener.diplomat.datomic-test"
                         "url-shortener.diplomat.cache-test"
                         "url-shortener.diplomat.producer-test"
                         "url-shortener.diplomat.consumer-test"]
            "test-integration" ["test" ":only"
                                "url-shortener.integration.api-test"
                                "url-shortener.integration.auth-api-test"]
            "test-all" ["test"]
            "coverage" ["cloverage" "--ns-exclude-regex" "url-shortener.core"]})
