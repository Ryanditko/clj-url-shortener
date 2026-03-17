(ns url-shortener.integration.api-test
  (:require [clojure.test :refer :all]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [clojure.data.json :as json]
            [datomic.api :as d]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.datomic.schema :as schema]
            [url-shortener.diplomat.http-server :as diplomat.http-server]))

(def test-uri "datomic:mem://url-shortener-api-test")

(def test-system (atom nil))
(def service-fn (atom nil))

(defrecord NoOpCache [])
(defrecord NoOpProducer [])

(defn setup-system []
  (d/delete-database test-uri)
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (schema/migrate! conn)
    (let [datomic (diplomat.datomic/map->Datomic {:uri test-uri :conn conn})
          cache (->NoOpCache)
          producer (->NoOpProducer)
          components {:datomic datomic :cache cache :producer producer}
          service-map (diplomat.http-server/build-service-map components {:port 0})
          servlet (http/create-servlet service-map)]
      (reset! test-system {:datomic datomic :conn conn})
      (reset! service-fn (::http/service-fn servlet)))))

(defn teardown-system []
  (reset! test-system nil)
  (reset! service-fn nil)
  (d/delete-database test-uri))

(use-fixtures :once
  (fn [f]
    (setup-system)
    (f)
    (teardown-system)))

(deftest health-check-test
  (testing "health endpoint returns 200"
    (let [response (test/response-for @service-fn :get "/health")]
      (is (= 200 (:status response)))
      (when (= 200 (:status response))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "ok" (:status body))))))))

(deftest create-url-integration-test
  (testing "creates a new shortened URL"
    (let [request-body (json/write-str {:original-url "https://example.com/integration-test"
                                         :owner "test@example.com"})
          response (test/response-for 
                    @service-fn 
                    :post "/api/urls"
                    :headers {"Content-Type" "application/json"}
                    :body request-body)]
      (is (= 201 (:status response)))
      (when (= 201 (:status response))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (string? (:id body)))
          (is (= "https://example.com/integration-test" (:original-url body)))
          (is (string? (:short-code body)))
          (is (>= (count (:short-code body)) 8))
          (is (string? (:short-url body)))
          (is (= 0 (:clicks body)))
          (is (= "test@example.com" (:owner body)))))))
  
  (testing "validates URL format"
    (let [request-body (json/write-str {:original-url "not-a-valid-url"})
          response (test/response-for 
                    @service-fn 
                    :post "/api/urls"
                    :headers {"Content-Type" "application/json"}
                    :body request-body)]
      (is (= 400 (:status response)))))
  
  (testing "creates minimal URL without optional fields"
    (let [request-body (json/write-str {:original-url "https://example.com/minimal"})
          response (test/response-for 
                    @service-fn 
                    :post "/api/urls"
                    :headers {"Content-Type" "application/json"}
                    :body request-body)]
      (is (= 201 (:status response)))
      (when (= 201 (:status response))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (nil? (:owner body)))
          (is (nil? (:expires-at body))))))))

(deftest redirect-integration-test
  (testing "redirects to original URL"
    (let [create-body (json/write-str {:original-url "https://example.com/redirect-test"})
          create-response (test/response-for 
                           @service-fn 
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)
              redirect-response (test/response-for 
                                 @service-fn 
                                 :get (str "/r/" short-code))]
          (is (= 302 (:status redirect-response)))
          (is (= "https://example.com/redirect-test" 
                 (get-in redirect-response [:headers "Location"])))))))
  
  (testing "returns 404 for non-existent code"
    (let [response (test/response-for @service-fn :get "/r/nonexistent123")]
      (is (= 404 (:status response)))))
  
  (testing "increments click counter on each redirect"
    (let [create-body (json/write-str {:original-url "https://example.com/click-test"})
          create-response (test/response-for 
                           @service-fn 
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)]
          (test/response-for @service-fn :get (str "/r/" short-code))
          (test/response-for @service-fn :get (str "/r/" short-code))
          (test/response-for @service-fn :get (str "/r/" short-code))
          (let [stats-response (test/response-for 
                                @service-fn 
                                :get (str "/api/urls/" short-code "/stats"))
                stats (json/read-str (:body stats-response) :key-fn keyword)]
            (is (>= (:total-clicks stats) 3))))))))

(deftest get-stats-integration-test
  (testing "returns statistics for URL"
    (let [create-body (json/write-str {:original-url "https://example.com/stats-test"})
          create-response (test/response-for 
                           @service-fn 
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)
              stats-response (test/response-for 
                              @service-fn 
                              :get (str "/api/urls/" short-code "/stats"))]
          (is (= 200 (:status stats-response)))
          (when (= 200 (:status stats-response))
            (let [stats (json/read-str (:body stats-response) :key-fn keyword)]
              (is (= short-code (:short-code stats)))
              (is (= "https://example.com/stats-test" (:original-url stats)))
              (is (number? (:total-clicks stats)))))))))
  
  (testing "returns 404 for non-existent code"
    (let [response (test/response-for 
                    @service-fn 
                    :get "/api/urls/nonexistent/stats")]
      (is (= 404 (:status response))))))

(deftest deactivate-url-integration-test
  (testing "deactivates a URL"
    (let [create-body (json/write-str {:original-url "https://example.com/deactivate-test"})
          create-response (test/response-for 
                           @service-fn 
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)
              deactivate-response (test/response-for 
                                   @service-fn 
                                   :delete (str "/api/urls/" short-code))]
          (is (= 204 (:status deactivate-response)))
          (let [redirect-response (test/response-for 
                                    @service-fn 
                                    :get (str "/r/" short-code))]
            (is (= 410 (:status redirect-response)))))))))

(deftest end-to-end-flow-test
  (testing "complete URL lifecycle"
    (let [create-body (json/write-str {:original-url "https://example.com/e2e-test"
                                        :owner "e2e@test.com"})
          create-resp (test/response-for 
                       @service-fn 
                       :post "/api/urls"
                       :headers {"Content-Type" "application/json"}
                       :body create-body)]
      (is (= 201 (:status create-resp)))
      (when (= 201 (:status create-resp))
        (let [url (json/read-str (:body create-resp) :key-fn keyword)
              code (:short-code url)]
          (is (= 0 (:clicks url)))
          
          (let [redirect-resp (test/response-for @service-fn :get (str "/r/" code))]
            (is (= 302 (:status redirect-resp))))
          
          (let [stats-resp (test/response-for @service-fn :get (str "/api/urls/" code "/stats"))
                stats (json/read-str (:body stats-resp) :key-fn keyword)]
            (is (= 200 (:status stats-resp)))
            (is (>= (:total-clicks stats) 1)))
          
          (let [deactivate-resp (test/response-for @service-fn :delete (str "/api/urls/" code))]
            (is (= 204 (:status deactivate-resp))))
          
          (let [redirect-resp (test/response-for @service-fn :get (str "/r/" code))]
            (is (= 410 (:status redirect-resp)))))))))
