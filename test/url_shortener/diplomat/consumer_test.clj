(ns url-shortener.diplomat.consumer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.datomic.schema :as schema]
            [url-shortener.diplomat.consumer :as consumer]
            [com.stuartsierra.component :as component]))

(def test-uri "datomic:mem://url-shortener-consumer-test")
(def test-datomic (atom nil))

(defn setup []
  (d/delete-database test-uri)
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (schema/migrate! conn)
    (reset! test-datomic (diplomat.datomic/map->Datomic {:uri test-uri :conn conn}))))

(defn teardown []
  (reset! test-datomic nil)
  (d/delete-database test-uri))

(use-fixtures :each
  (fn [f]
    (setup)
    (f)
    (teardown)))

(deftest consumer-component-lifecycle-test
  (testing "consumer starts gracefully even without Kafka"
    (let [config {:kafka-bootstrap-servers "localhost:19092"}
          consumer-component (consumer/new-consumer config)
          started (component/start (assoc consumer-component :datomic @test-datomic))]
      (is (some? started))
      (component/stop started)))

  (testing "stop clears consumer references"
    (let [stopped (component/stop
                   (consumer/map->Consumer {:config {} :consumer nil
                                            :running? nil :poll-thread nil}))]
      (is (nil? (:consumer stopped)))
      (is (nil? (:running? stopped)))
      (is (nil? (:poll-thread stopped))))))

(deftest process-accessed-event-via-analytics-test
  (testing "analytics are created for a new short-code and day"
    (let [datomic @test-datomic]
      (diplomat.datomic/save-daily-analytics!
       datomic
       {:analytics/short-code "test123"
        :analytics/date #inst "2026-03-19T00:00:00.000-00:00"
        :analytics/clicks 1
        :analytics/unique-visitors 1})
      (let [analytics (diplomat.datomic/find-all-daily-analytics datomic "test123")]
        (is (= 1 (count analytics)))
        (is (= 1 (:analytics/clicks (first analytics))))
        (is (= 1 (:analytics/unique-visitors (first analytics)))))))

  (testing "analytics are updated when same day event arrives"
    (let [datomic @test-datomic]
      (diplomat.datomic/save-daily-analytics!
       datomic
       {:analytics/short-code "test456"
        :analytics/date #inst "2026-03-19T00:00:00.000-00:00"
        :analytics/clicks 1
        :analytics/unique-visitors 1})
      (diplomat.datomic/save-daily-analytics!
       datomic
       {:analytics/short-code "test456"
        :analytics/date #inst "2026-03-19T00:00:00.000-00:00"
        :analytics/clicks 5
        :analytics/unique-visitors 3})
      (let [analytics (diplomat.datomic/find-all-daily-analytics datomic "test456")]
        (is (= 1 (count analytics)))
        (is (= 5 (:analytics/clicks (first analytics))))
        (is (= 3 (:analytics/unique-visitors (first analytics)))))))

  (testing "multiple days produce separate analytics entries"
    (let [datomic @test-datomic]
      (diplomat.datomic/save-daily-analytics!
       datomic
       {:analytics/short-code "multiday"
        :analytics/date #inst "2026-03-18T00:00:00.000-00:00"
        :analytics/clicks 10
        :analytics/unique-visitors 5})
      (diplomat.datomic/save-daily-analytics!
       datomic
       {:analytics/short-code "multiday"
        :analytics/date #inst "2026-03-19T00:00:00.000-00:00"
        :analytics/clicks 7
        :analytics/unique-visitors 3})
      (let [analytics (diplomat.datomic/find-all-daily-analytics datomic "multiday")]
        (is (= 2 (count analytics)))
        (is (= 10 (:analytics/clicks (first analytics))))
        (is (= 7 (:analytics/clicks (second analytics))))))))

(deftest find-all-daily-analytics-empty-test
  (testing "returns empty collection for unknown short-code"
    (let [analytics (diplomat.datomic/find-all-daily-analytics @test-datomic "nonexistent")]
      (is (empty? analytics)))))
