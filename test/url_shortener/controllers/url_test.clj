(ns url-shortener.controllers.url-test
  (:require [clojure.test :refer :all]
            [url-shortener.controllers.url :as controllers]
            [url-shortener.logic.shortener :as logic]
            [url-shortener.adapters.url :as adapters.url]))

(def test-state (atom {}))

(defrecord MockDatomic []
  controllers/IDatomic
  (save-url! [_ url]
    (swap! test-state assoc-in [:urls (:url/short-code url)] url)
    nil)
  
  (find-url-by-short-code [_ short-code]
    (get-in @test-state [:urls short-code]))
  
  (update-url! [_ url]
    (swap! test-state assoc-in [:urls (:url/short-code url)] url)
    nil))

(defrecord MockProducer []
  controllers/IProducer
  (publish-url-created! [_ event]
    (swap! test-state update :created-events conj event)
    nil)
  
  (publish-url-accessed! [_ event]
    (swap! test-state update :accessed-events conj event)
    nil))

(use-fixtures :each
  (fn [f]
    (reset! test-state {:urls {}
                        :created-events []
                        :accessed-events []})
    (f)))

(deftest create-url!-test
  (let [datomic (->MockDatomic)
        producer (->MockProducer)]
    
    (testing "creates a valid URL"
      (let [url (controllers/create-url! 
                 "https://example.com/long/url"
                 {}
                 datomic
                 producer)]
        (is (uuid? (:id url)))
        (is (= "https://example.com/long/url" (:original-url url)))
        (is (string? (:short-code url)))
        (is (>= (count (:short-code url)) 8))
        (is (= 0 (:clicks url)))
        (is (true? (:active? url)))))
    
    (testing "saves URL to datomic"
      (controllers/create-url! 
       "https://example.com/test"
       {}
       datomic
       producer)
      (is (pos? (count (:urls @test-state)))))
    
    (testing "publishes creation event"
      (controllers/create-url! 
       "https://example.com/event-test"
       {}
       datomic
       producer)
      (is (pos? (count (:created-events @test-state)))))
    
    (testing "validates URL format"
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo
           #"Invalid URL format"
           (controllers/create-url! 
            "not-a-url"
            {}
            datomic
            producer))))
    
    (testing "supports optional owner"
      (let [url (controllers/create-url! 
                 "https://example.com/owned"
                 {:owner "user@example.com"}
                 datomic
                 producer)]
        (is (= "user@example.com" (:owner url)))))
    
    (testing "supports optional expiration"
      (let [url (controllers/create-url! 
                 "https://example.com/expires"
                 {:expires-at "2024-12-31T23:59:59Z"}
                 datomic
                 producer)]
        (is (inst? (:expires-at url)))))))

(deftest redirect-url!-test
  (let [datomic (->MockDatomic)
        producer (->MockProducer)]
    
    (testing "redirects to existing URL"
      (let [created-url (controllers/create-url! 
                         "https://example.com/redirect"
                         {}
                         datomic
                         producer)
            redirected-url (controllers/redirect-url! 
                            (:short-code created-url)
                            datomic
                            producer)]
        (is (= (:original-url created-url) (:original-url redirected-url)))))
    
    (testing "increments click counter"
      (let [created-url (controllers/create-url! 
                         "https://example.com/clicks"
                         {}
                         datomic
                         producer)
            _ (controllers/redirect-url! (:short-code created-url) datomic producer)
            redirected-url (controllers/redirect-url! (:short-code created-url) datomic producer)]
        (is (= 2 (:clicks redirected-url)))))
    
    (testing "publishes access event"
      (let [created-url (controllers/create-url! 
                         "https://example.com/event"
                         {}
                         datomic
                         producer)
            initial-events (count (:accessed-events @test-state))]
        (controllers/redirect-url! (:short-code created-url) datomic producer)
        (is (= (inc initial-events) (count (:accessed-events @test-state))))))
    
    (testing "throws on non-existent short code"
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo
           #"Short code not found"
           (controllers/redirect-url! 
            "nonexistent"
            datomic
            producer))))
    
    (testing "throws on expired URL"
      (let [past-date "2020-01-01T00:00:00Z"
            created-url (controllers/create-url! 
                         "https://example.com/expired"
                         {:expires-at past-date}
                         datomic
                         producer)]
        (is (thrown-with-msg? 
             clojure.lang.ExceptionInfo
             #"URL has expired"
             (controllers/redirect-url! 
              (:short-code created-url)
              datomic
              producer)))))))

(deftest concurrent-access-test
  (let [datomic (->MockDatomic)
        producer (->MockProducer)]
    
    (testing "handles multiple clicks correctly"
      (let [created-url (controllers/create-url! 
                         "https://example.com/multiple-clicks"
                         {}
                         datomic
                         producer)
            short-code (:short-code created-url)]
        (dotimes [_ 10]
          (controllers/redirect-url! short-code datomic producer))
        (let [final-url (controllers/find-url-by-short-code datomic short-code)
              final-model (adapters.url/datomic->model final-url)]
          (is (= 10 (:clicks final-model))))))))
