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
    nil)

  (save-click-event! [_ event]
    (swap! test-state update :click-events conj event)
    nil)

  (find-click-events-by-short-code [_ short-code]
    (->> (:click-events @test-state)
         (filter #(= short-code (:click/short-code %))))))

(defrecord MockCache []
  controllers/ICache
  (cache-url! [_ _cached-url _ttl] nil)
  (get-cached-url [_ _short-code] nil)
  (invalidate-url! [_ _short-code] nil))

(defrecord MockProducer []
  controllers/IProducer
  (publish-url-created! [_ event]
    (swap! test-state update :created-events conj event)
    nil)
  
  (publish-url-accessed! [_ event]
    (swap! test-state update :accessed-events conj event)
    nil)

  (publish-url-deactivated! [_ event]
    (swap! test-state update :deactivated-events conj event)
    nil))

(use-fixtures :each
  (fn [f]
    (reset! test-state {:urls {}
                        :click-events []
                        :created-events []
                        :accessed-events []
                        :deactivated-events []})
    (f)))

(deftest create-url!-test
  (let [datomic (->MockDatomic)
        cache (->MockCache)
        producer (->MockProducer)]
    
    (testing "creates a valid URL"
      (let [url (controllers/create-url! 
                 "https://example.com/long/url"
                 {}
                 datomic cache producer)]
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
       datomic cache producer)
      (is (pos? (count (:urls @test-state)))))
    
    (testing "publishes creation event"
      (controllers/create-url! 
       "https://example.com/event-test"
       {}
       datomic cache producer)
      (is (pos? (count (:created-events @test-state)))))
    
    (testing "validates URL format"
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo
           #"Invalid URL format"
           (controllers/create-url! 
            "not-a-url"
            {}
            datomic cache producer))))
    
    (testing "supports optional owner"
      (let [url (controllers/create-url! 
                 "https://example.com/owned"
                 {:owner "user@example.com"}
                 datomic cache producer)]
        (is (= "user@example.com" (:owner url)))))
    
    (testing "supports optional expiration"
      (let [url (controllers/create-url! 
                 "https://example.com/expires"
                 {:expires-at "2024-12-31T23:59:59Z"}
                 datomic cache producer)]
        (is (inst? (:expires-at url)))))))

(deftest redirect-url!-test
  (let [datomic (->MockDatomic)
        cache (->MockCache)
        producer (->MockProducer)]
    
    (testing "redirects to existing URL"
      (let [created-url (controllers/create-url! 
                         "https://example.com/redirect"
                         {}
                         datomic cache producer)
            redirected-url (controllers/redirect-url! 
                            (:short-code created-url)
                            {}
                            datomic cache producer)]
        (is (= (:original-url created-url) (:original-url redirected-url)))))
    
    (testing "increments click counter"
      (let [created-url (controllers/create-url! 
                         "https://example.com/clicks"
                         {}
                         datomic cache producer)
            _ (controllers/redirect-url! (:short-code created-url) {} datomic cache producer)
            redirected-url (controllers/redirect-url! (:short-code created-url) {} datomic cache producer)]
        (is (= 2 (:clicks redirected-url)))))
    
    (testing "publishes access event"
      (let [created-url (controllers/create-url! 
                         "https://example.com/event"
                         {}
                         datomic cache producer)
            initial-events (count (:accessed-events @test-state))]
        (controllers/redirect-url! (:short-code created-url) {} datomic cache producer)
        (is (= (inc initial-events) (count (:accessed-events @test-state))))))

    (testing "saves click event to datomic"
      (let [initial-clicks (count (:click-events @test-state))
            created-url (controllers/create-url!
                         "https://example.com/click-track"
                         {}
                         datomic cache producer)]
        (controllers/redirect-url! (:short-code created-url) {} datomic cache producer)
        (is (= (inc initial-clicks) (count (:click-events @test-state))))))
    
    (testing "throws on non-existent short code"
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo
           #"Short code not found"
           (controllers/redirect-url! 
            "nonexistent"
            {}
            datomic cache producer))))
    
    (testing "throws on expired URL"
      (let [past-date "2020-01-01T00:00:00Z"
            created-url (controllers/create-url! 
                         "https://example.com/expired"
                         {:expires-at past-date}
                         datomic cache producer)]
        (is (thrown-with-msg? 
             clojure.lang.ExceptionInfo
             #"URL has expired"
             (controllers/redirect-url! 
              (:short-code created-url)
              {}
              datomic cache producer)))))))

(deftest get-url-stats-test
  (let [datomic (->MockDatomic)
        cache (->MockCache)
        producer (->MockProducer)]

    (testing "returns stats for existing URL"
      (let [created-url (controllers/create-url!
                         "https://example.com/stats"
                         {}
                         datomic cache producer)
            _ (controllers/redirect-url! (:short-code created-url) {} datomic cache producer)
            {:keys [stats original-url]} (controllers/get-url-stats
                                           (:short-code created-url)
                                           datomic)]
        (is (= "https://example.com/stats" original-url))
        (is (= (:short-code created-url) (:short-code stats)))
        (is (= 1 (:total-clicks stats)))))

    (testing "throws on non-existent short code"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Short code not found"
           (controllers/get-url-stats "nonexistent" datomic))))))

(deftest deactivate-url!-test
  (let [datomic (->MockDatomic)
        cache (->MockCache)
        producer (->MockProducer)]

    (testing "deactivates an existing URL"
      (let [created-url (controllers/create-url!
                         "https://example.com/deactivate"
                         {}
                         datomic cache producer)]
        (controllers/deactivate-url! (:short-code created-url) datomic cache producer)
        (let [url-datomic (controllers/find-url-by-short-code datomic (:short-code created-url))
              url (adapters.url/datomic->model url-datomic)]
          (is (false? (:active? url))))))

    (testing "publishes deactivation event"
      (let [initial-events (count (:deactivated-events @test-state))
            created-url (controllers/create-url!
                         "https://example.com/deactivate-event"
                         {}
                         datomic cache producer)]
        (controllers/deactivate-url! (:short-code created-url) datomic cache producer)
        (is (= (inc initial-events) (count (:deactivated-events @test-state))))))

    (testing "redirect throws after deactivation"
      (let [created-url (controllers/create-url!
                         "https://example.com/deactivate-redirect"
                         {}
                         datomic cache producer)]
        (controllers/deactivate-url! (:short-code created-url) datomic cache producer)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"URL has been deactivated"
             (controllers/redirect-url!
              (:short-code created-url)
              {}
              datomic cache producer)))))

    (testing "throws on non-existent short code"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Short code not found"
           (controllers/deactivate-url! "nonexistent" datomic cache producer))))))

(deftest concurrent-access-test
  (let [datomic (->MockDatomic)
        cache (->MockCache)
        producer (->MockProducer)]
    
    (testing "handles multiple clicks correctly"
      (let [created-url (controllers/create-url! 
                         "https://example.com/multiple-clicks"
                         {}
                         datomic cache producer)
            short-code (:short-code created-url)]
        (dotimes [_ 10]
          (controllers/redirect-url! short-code {} datomic cache producer))
        (let [final-url (controllers/find-url-by-short-code datomic short-code)
              final-model (adapters.url/datomic->model final-url)]
          (is (= 10 (:clicks final-model))))))))
