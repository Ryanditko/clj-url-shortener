(ns url-shortener.diplomat.cache-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [url-shortener.diplomat.cache :as cache]))

(deftest cache-component-lifecycle-test
  (testing "start configures conn-spec from config"
    (let [config {:redis-host "127.0.0.1" :redis-port 6380}
          started (component/start (cache/new-cache config))]
      (is (some? (:conn-spec started)))
      (is (= "127.0.0.1" (get-in started [:conn-spec :spec :host])))
      (is (= 6380 (get-in started [:conn-spec :spec :port])))))

  (testing "start uses defaults when config keys are missing"
    (let [started (component/start (cache/new-cache {}))]
      (is (= "localhost" (get-in started [:conn-spec :spec :host])))
      (is (= 6379 (get-in started [:conn-spec :spec :port])))))

  (testing "start is idempotent when already started"
    (let [started (component/start (cache/new-cache {:redis-host "localhost"}))
          restarted (component/start started)]
      (is (identical? (:conn-spec started) (:conn-spec restarted)))))

  (testing "stop clears conn-spec"
    (let [started (component/start (cache/new-cache {}))
          stopped (component/stop started)]
      (is (nil? (:conn-spec stopped))))))

(deftest cache-graceful-failure-test
  (let [broken-cache (cache/map->Cache {:config {} :conn-spec nil})]

    (testing "set-url! returns nil on failure without throwing"
      (is (nil? (cache/set-url! broken-cache {:short-code "abc"} 60))))

    (testing "get-url returns nil on failure without throwing"
      (is (nil? (cache/get-url broken-cache "abc"))))

    (testing "delete-url! returns nil on failure without throwing"
      (is (nil? (cache/delete-url! broken-cache "abc"))))

    (testing "set-stats! returns nil on failure without throwing"
      (is (nil? (cache/set-stats! broken-cache {:short-code "abc"} 60))))

    (testing "get-stats returns nil on failure without throwing"
      (is (nil? (cache/get-stats broken-cache "abc"))))))
