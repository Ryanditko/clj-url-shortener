(ns url-shortener.diplomat.producer-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [url-shortener.diplomat.producer :as producer]))

(deftest producer-component-lifecycle-test
  (testing "start creates producer instance even with unreachable broker"
    (let [config {:kafka-bootstrap-servers "localhost:19092"}
          started (component/start (producer/new-producer config))]
      (is (some? (:producer started)))
      (component/stop started)))

  (testing "stop clears producer reference"
    (let [started (producer/map->Producer {:config {} :producer nil})
          stopped (component/stop started)]
      (is (nil? (:producer stopped))))))

(deftest producer-graceful-failure-test
  (let [nil-producer (producer/map->Producer {:config {} :producer nil})]

    (testing "publish-event! skips when producer is nil"
      (is (nil? (producer/publish-event! nil-producer "topic" {:event-id "1"}))))

    (testing "publish-url-created! skips when producer is nil"
      (is (nil? (producer/publish-url-created! nil-producer {:event-id "1"}))))

    (testing "publish-url-accessed! skips when producer is nil"
      (is (nil? (producer/publish-url-accessed! nil-producer {:event-id "1"}))))

    (testing "publish-url-deactivated! skips when producer is nil"
      (is (nil? (producer/publish-url-deactivated! nil-producer {:event-id "1"}))))))

(deftest producer-topic-routing-test
  (let [published (atom [])
        mock-kafka-producer (reify
                              org.apache.kafka.clients.producer.Producer
                              (send [_ record]
                                (swap! published conj
                                       {:topic (.topic record)
                                        :key (.key record)})
                                (let [p (promise)] (deliver p nil) p))
                              (send [_ record _callback]
                                (swap! published conj
                                       {:topic (.topic record)
                                        :key (.key record)})
                                (let [p (promise)] (deliver p nil) p))
                              (close [_]))
        prod (producer/map->Producer {:config {} :producer mock-kafka-producer})]

    (testing "publish-url-created! routes to correct topic"
      (producer/publish-url-created! prod {:event-id "evt-1"})
      (is (= "url-shortener.url.created" (:topic (last @published)))))

    (testing "publish-url-accessed! routes to correct topic"
      (producer/publish-url-accessed! prod {:event-id "evt-2"})
      (is (= "url-shortener.url.accessed" (:topic (last @published)))))

    (testing "publish-url-deactivated! routes to correct topic"
      (producer/publish-url-deactivated! prod {:event-id "evt-3"})
      (is (= "url-shortener.url.deactivated" (:topic (last @published)))))))
