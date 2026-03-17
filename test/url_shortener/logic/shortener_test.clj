(ns url-shortener.logic.shortener-test
  (:require [clojure.test :refer :all]
            [url-shortener.logic.shortener :as shortener]))

(deftest number->base62-test
  (testing "converts zero correctly"
    (is (= "0" (shortener/number->base62 0))))
  
  (testing "converts positive numbers"
    (is (= "1" (shortener/number->base62 1)))
    (is (= "A" (shortener/number->base62 10)))
    (is (= "a" (shortener/number->base62 36)))
    (is (= "z" (shortener/number->base62 61)))
    (is (= "10" (shortener/number->base62 62))))
  
  (testing "converts large numbers"
    (is (= "8M0kX" (shortener/number->base62 123456789)))))

(deftest base62->number-test
  (testing "converts base62 strings to numbers"
    (is (= 0 (shortener/base62->number "0")))
    (is (= 1 (shortener/base62->number "1")))
    (is (= 10 (shortener/base62->number "A")))
    (is (= 36 (shortener/base62->number "a")))
    (is (= 61 (shortener/base62->number "z")))
    (is (= 62 (shortener/base62->number "10")))
    (is (= 123456789 (shortener/base62->number "8M0kX"))))
  
  (testing "throws on invalid characters"
    (is (thrown? Exception (shortener/base62->number "!")))
    (is (thrown? Exception (shortener/base62->number "abc@")))))

(deftest base62-roundtrip-test
  (testing "base62 conversion is reversible"
    (doseq [n [0 1 10 100 1000 10000 123456789]]
      (is (= n (-> n shortener/number->base62 shortener/base62->number))))))

(deftest generate-short-code-from-id-test
  (testing "generates deterministic codes"
    (is (= "1" (shortener/generate-short-code-from-id 1)))
    (is (= "A" (shortener/generate-short-code-from-id 10)))
    (is (= "8M0kX" (shortener/generate-short-code-from-id 123456789))))
  
  (testing "same ID always generates same code"
    (let [id 99999
          code1 (shortener/generate-short-code-from-id id)
          code2 (shortener/generate-short-code-from-id id)]
      (is (= code1 code2)))))

(deftest generate-short-code-from-timestamp-test
  (testing "generates code of specified length"
    (let [ts (System/currentTimeMillis)
          code (shortener/generate-short-code-from-timestamp ts 8)]
      (is (>= (count code) 8))))
  
  (testing "includes timestamp in code"
    (let [ts 123456789
          code (shortener/generate-short-code-from-timestamp ts 8)]
      (is (clojure.string/starts-with? code (shortener/number->base62 ts))))))

(deftest valid-url?-test
  (testing "accepts valid URLs"
    (is (true? (shortener/valid-url? "http://example.com")))
    (is (true? (shortener/valid-url? "https://example.com")))
    (is (true? (shortener/valid-url? "https://example.com/path?query=value")))
    (is (true? (shortener/valid-url? "http://sub.domain.example.com:8080/path"))))
  
  (testing "rejects invalid URLs"
    (is (false? (shortener/valid-url? "")))
    (is (false? (shortener/valid-url? "not-a-url")))
    (is (false? (shortener/valid-url? "ftp://example.com")))
    (is (false? (shortener/valid-url? nil))))
  
  (testing "rejects URLs that are too long"
    (let [long-url (str "http://example.com/" (apply str (repeat 2050 "a")))]
      (is (false? (shortener/valid-url? long-url))))))

(deftest valid-custom-code?-test
  (testing "accepts valid custom codes"
    (is (true? (shortener/valid-custom-code? "abc")))
    (is (true? (shortener/valid-custom-code? "abc123")))
    (is (true? (shortener/valid-custom-code? "MyCode123"))))
  
  (testing "rejects invalid custom codes"
    (is (false? (shortener/valid-custom-code? "ab")))
    (is (false? (shortener/valid-custom-code? "a")))
    (is (false? (shortener/valid-custom-code? "abc-123")))
    (is (false? (shortener/valid-custom-code? "abc_123")))
    (is (false? (shortener/valid-custom-code? "abc 123"))))
  
  (testing "rejects reserved words"
    (is (false? (shortener/valid-custom-code? "api")))
    (is (false? (shortener/valid-custom-code? "admin")))
    (is (false? (shortener/valid-custom-code? "stats")))
    (is (false? (shortener/valid-custom-code? "API")))
    (is (false? (shortener/valid-custom-code? "Admin")))))

(deftest url-expired?-test
  (let [now (java.util.Date.)
        past (java.util.Date. (- (.getTime now) 10000))
        future (java.util.Date. (+ (.getTime now) 10000))]
    
    (testing "detects expired URLs"
      (is (true? (shortener/url-expired? {:expires-at past} now))))
    
    (testing "detects non-expired URLs"
      (is (false? (shortener/url-expired? {:expires-at future} now))))
    
    (testing "handles URLs without expiration"
      (is (nil? (shortener/url-expired? {} now))))))

(deftest calculate-expiration-test
  (testing "calculates expiration correctly"
    (let [start (java.util.Date. 1704067200000)
          expiration (shortener/calculate-expiration start 7)]
      (is (inst? expiration))
      (is (< (.getTime start) (.getTime expiration))))))

(deftest increment-clicks-test
  (testing "increments existing clicks"
    (is (= 43 (:clicks (shortener/increment-clicks {:clicks 42})))))
  
  (testing "initializes clicks from nil"
    (is (= 1 (:clicks (shortener/increment-clicks {}))))))

(deftest calculate-stats-test
  (let [url {:short-code "abc123"
             :created-at (java.util.Date.)}
        click-events [{:timestamp (java.util.Date.)
                       :ip-address "192.168.1.1"}
                      {:timestamp (java.util.Date.)
                       :ip-address "192.168.1.2"}
                      {:timestamp (java.util.Date.)
                       :ip-address "192.168.1.1"}]]
    
    (testing "calculates total clicks"
      (let [stats (shortener/calculate-stats url click-events)]
        (is (= 3 (:total-clicks stats)))))
    
    (testing "calculates unique visitors"
      (let [stats (shortener/calculate-stats url click-events)]
        (is (= 2 (:unique-visitors stats)))))
    
    (testing "finds last accessed timestamp"
      (let [stats (shortener/calculate-stats url click-events)]
        (is (inst? (:last-accessed stats)))))))

(deftest codes-collide?-test
  (testing "detects collisions"
    (is (true? (shortener/codes-collide? "abc123" "abc123")))
    (is (false? (shortener/codes-collide? "abc123" "def456")))))

(deftest generate-alternative-code-test
  (testing "generates alternative code"
    (let [original "abc123"
          alternative (shortener/generate-alternative-code original)]
      (is (> (count alternative) (count original)))
      (is (clojure.string/starts-with? alternative original)))))
