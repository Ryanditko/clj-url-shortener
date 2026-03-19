(ns url-shortener.logic.rate-limiter-test
  (:require [clojure.test :refer [deftest is testing]]
            [url-shortener.logic.rate-limiter :as rate-limiter]))

(deftest create-limiter-test
  (testing "creates a limiter with empty state"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 5 :refill-per-second 1.0}})]
      (is (map? limiter))
      (is (instance? clojure.lang.Atom (:buckets limiter))))))

(deftest check-rate-test
  (testing "allows requests within limit"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 3 :refill-per-second 0.5}})]
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api))))
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api))))
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api))))))

  (testing "blocks requests exceeding limit"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 2 :refill-per-second 0.5}})]
      (rate-limiter/check-rate! limiter "1.2.3.4" :api)
      (rate-limiter/check-rate! limiter "1.2.3.4" :api)
      (let [result (rate-limiter/check-rate! limiter "1.2.3.4" :api)]
        (is (false? (:allowed? result)))
        (is (pos? (:retry-after result))))))

  (testing "different IPs have separate limits"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 1 :refill-per-second 0.1}})]
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.1.1.1" :api))))
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "2.2.2.2" :api))))
      (is (false? (:allowed? (rate-limiter/check-rate! limiter "1.1.1.1" :api))))))

  (testing "different limit keys have separate buckets"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 1 :refill-per-second 0.1}
                                                 :login {:max-tokens 1 :refill-per-second 0.1}})]
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api))))
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :login))))))

  (testing "tokens refill over time"
    (let [limiter (rate-limiter/create-limiter {:api {:max-tokens 1 :refill-per-second 10.0}})]
      (rate-limiter/check-rate! limiter "1.2.3.4" :api)
      (is (false? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api))))
      (Thread/sleep 200)
      (is (true? (:allowed? (rate-limiter/check-rate! limiter "1.2.3.4" :api)))))))
