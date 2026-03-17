(ns url-shortener.adapters.url-test
  (:require [clojure.test :refer :all]
            [url-shortener.adapters.url :as adapters]
            [url-shortener.models.url :as models]))

(def sample-wire-request
  {:original-url "https://example.com/long/url"
   :owner "user@example.com"
   :expires-at "2024-12-31T23:59:59Z"})

(def sample-generated-data
  {:id #uuid "123e4567-e89b-12d3-a456-426614174000"
   :short-code "abc123"
   :created-at #inst "2024-01-15T10:30:00.000-00:00"})

(def sample-url-model
  {:id #uuid "123e4567-e89b-12d3-a456-426614174000"
   :original-url "https://example.com/long/url"
   :short-code "abc123"
   :created-at #inst "2024-01-15T10:30:00.000-00:00"
   :clicks 42
   :expires-at #inst "2024-12-31T23:59:59.000-00:00"
   :owner "user@example.com"
   :active? true})

(deftest wire-request->model-test
  (testing "converts wire request to model"
    (let [model (adapters/wire-request->model sample-wire-request sample-generated-data)]
      (is (= (:id sample-generated-data) (:id model)))
      (is (= "https://example.com/long/url" (:original-url model)))
      (is (= "abc123" (:short-code model)))
      (is (= 0 (:clicks model)))
      (is (true? (:active? model)))))
  
  (testing "handles missing optional fields"
    (let [minimal-request {:original-url "https://example.com"}
          model (adapters/wire-request->model minimal-request sample-generated-data)]
      (is (nil? (:owner model)))
      (is (nil? (:expires-at model))))))

(deftest model->wire-response-test
  (testing "converts model to wire response"
    (let [response (adapters/model->wire-response sample-url-model "https://sho.rt")]
      (is (= "123e4567-e89b-12d3-a456-426614174000" (:id response)))
      (is (= "https://example.com/long/url" (:original-url response)))
      (is (= "abc123" (:short-code response)))
      (is (= "https://sho.rt/abc123" (:short-url response)))
      (is (= 42 (:clicks response)))
      (is (string? (:created-at response)))))
  
  (testing "handles optional fields"
    (let [minimal-model {:id #uuid "123e4567-e89b-12d3-a456-426614174000"
                         :original-url "https://example.com"
                         :short-code "abc123"
                         :created-at #inst "2024-01-15T10:30:00.000-00:00"
                         :clicks 0
                         :active? true}
          response (adapters/model->wire-response minimal-model "https://sho.rt")]
      (is (nil? (:expires-at response)))
      (is (nil? (:owner response))))))

(deftest model->redirect-response-test
  (testing "creates successful redirect"
    (let [response (adapters/model->redirect-response 
                    sample-url-model
                    #inst "2024-01-15T10:30:00.000-00:00")]
      (is (= 302 (:status response)))
      (is (= "https://example.com/long/url" (get-in response [:headers "Location"])))))
  
  (testing "handles deactivated URL"
    (let [deactivated-url (assoc sample-url-model :active? false)
          response (adapters/model->redirect-response 
                    deactivated-url
                    #inst "2024-01-15T10:30:00.000-00:00")]
      (is (= 410 (:status response)))
      (is (string? (:body response)))))
  
  (testing "handles expired URL"
    (let [response (adapters/model->redirect-response 
                    sample-url-model
                    #inst "2025-01-01T00:00:00.000-00:00")]
      (is (= 410 (:status response)))
      (is (string? (:body response))))))

(deftest model->cache-test
  (testing "converts model to cache format"
    (let [cached (adapters/model->cache sample-url-model)]
      (is (= "abc123" (:short-code cached)))
      (is (= "https://example.com/long/url" (:original-url cached)))
      (is (= 42 (:clicks cached)))
      (is (string? (:created-at cached)))
      (is (string? (:expires-at cached))))))

(deftest cache->model-test
  (testing "converts cache to model format"
    (let [cached {:short-code "abc123"
                  :original-url "https://example.com/long/url"
                  :clicks 42
                  :created-at "2024-01-15T10:30:00.000Z"
                  :expires-at "2024-12-31T23:59:59.000Z"
                  :active? true}
          model (adapters/cache->model cached #uuid "123e4567-e89b-12d3-a456-426614174000")]
      (is (= "abc123" (:short-code model)))
      (is (= 42 (:clicks model)))
      (is (inst? (:created-at model)))
      (is (inst? (:expires-at model))))))

(deftest model->datomic-test
  (testing "converts model to datomic format"
    (let [datomic (adapters/model->datomic sample-url-model)]
      (is (= #uuid "123e4567-e89b-12d3-a456-426614174000" (:url/id datomic)))
      (is (= "https://example.com/long/url" (:url/original-url datomic)))
      (is (= "abc123" (:url/short-code datomic)))
      (is (= 42 (:url/clicks datomic)))
      (is (inst? (:url/created-at datomic))))))

(deftest datomic->model-test
  (testing "converts datomic to model format"
    (let [datomic {:url/id #uuid "123e4567-e89b-12d3-a456-426614174000"
                   :url/original-url "https://example.com/long/url"
                   :url/short-code "abc123"
                   :url/created-at #inst "2024-01-15T10:30:00.000-00:00"
                   :url/clicks 42
                   :url/expires-at #inst "2024-12-31T23:59:59.000-00:00"
                   :url/owner "user@example.com"
                   :url/active? true
                   :db/id 12345}
          model (adapters/datomic->model datomic)]
      (is (= "abc123" (:short-code model)))
      (is (= 42 (:clicks model)))
      (is (true? (:active? model))))))

(deftest model->url-created-event-test
  (testing "converts model to kafka event"
    (let [event (adapters/model->url-created-event sample-url-model)]
      (is (= "url.created" (:event-type event)))
      (is (= "abc123" (:short-code event)))
      (is (= "https://example.com/long/url" (:original-url event)))
      (is (string? (:event-id event)))
      (is (string? (:timestamp event))))))

(deftest click-event->kafka-event-test
  (testing "converts click event to kafka event"
    (let [click-event {:event-id #uuid "987e6543-e21b-45d3-a987-123456789abc"
                       :short-code "abc123"
                       :timestamp #inst "2024-01-15T15:45:00.000-00:00"
                       :user-agent "Mozilla/5.0"
                       :ip-address "192.168.1.1"}
          event (adapters/click-event->kafka-event click-event "https://example.com")]
      (is (= "url.accessed" (:event-type event)))
      (is (= "abc123" (:short-code event)))
      (is (= "https://example.com" (:original-url event)))
      (is (= "Mozilla/5.0" (:user-agent event))))))

(deftest roundtrip-conversions-test
  (testing "model -> datomic -> model is stable"
    (let [datomic (adapters/model->datomic sample-url-model)
          model-again (adapters/datomic->model datomic)]
      (is (= (:short-code sample-url-model) (:short-code model-again)))
      (is (= (:clicks sample-url-model) (:clicks model-again))))))
