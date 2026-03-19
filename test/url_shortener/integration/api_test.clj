(ns url-shortener.integration.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [clojure.data.json :as json]
            [datomic.api :as d]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.datomic.schema :as schema]
            [url-shortener.diplomat.http-server :as diplomat.http-server]
            [url-shortener.logic.auth :as auth]))

(def test-uri "datomic:mem://url-shortener-api-test")
(def test-jwt-secret "test-secret-for-integration-tests-min-32-chars!")

(def test-system (atom nil))
(def service-fn (atom nil))

(defrecord NoOpCache [])
(defrecord NoOpProducer [])

(def test-config {:port 0
                  :jwt-secret test-jwt-secret
                  :jwt-ttl-minutes 60
                  :rate-limiter {:api {:max-tokens 1000 :refill-per-second 100.0}
                                 :redirect {:max-tokens 1000 :refill-per-second 100.0}
                                 :auth {:max-tokens 1000 :refill-per-second 100.0}}})

(defn setup-system []
  (d/delete-database test-uri)
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (schema/migrate! conn)
    (let [datomic (diplomat.datomic/map->Datomic {:uri test-uri :conn conn})
          cache (->NoOpCache)
          producer (->NoOpProducer)
          components {:datomic datomic :cache cache :producer producer}
          service-map (diplomat.http-server/build-service-map-with-auth components test-config)
          servlet (http/create-servlet service-map)]
      (reset! test-system {:datomic datomic :conn conn})
      (reset! service-fn (::http/service-fn servlet)))))

(defn teardown-system []
  (reset! test-system nil)
  (reset! service-fn nil)
  (d/delete-database test-uri))

(defn- get-auth-token! []
  (let [username (str "testuser-" (java.util.UUID/randomUUID))
        password "testpassword123"
        _ (test/response-for @service-fn :post "/api/auth/register"
                             :headers {"Content-Type" "application/json"}
                             :body (json/write-str {:username username :password password}))
        resp (test/response-for @service-fn :post "/api/auth/login"
                                :headers {"Content-Type" "application/json"}
                                :body (json/write-str {:username username :password password}))]
    (:token (json/read-str (:body resp) :key-fn keyword))))

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

(deftest metrics-endpoint-test
  (testing "metrics endpoint returns prometheus text format"
    (let [response (test/response-for @service-fn :get "/metrics")]
      (is (= 200 (:status response)))
      (is (string? (:body response))))))

(deftest register-and-login-test
  (testing "register a user and then login"
    (let [reg-body (json/write-str {:username "apiuser" :password "testpassword123"})
          reg-resp (test/response-for
                    @service-fn
                    :post "/api/auth/register"
                    :headers {"Content-Type" "application/json"}
                    :body reg-body)]
      (is (= 201 (:status reg-resp)))
      (when (= 201 (:status reg-resp))
        (let [login-body (json/write-str {:username "apiuser" :password "testpassword123"})
              login-resp (test/response-for
                          @service-fn
                          :post "/api/auth/login"
                          :headers {"Content-Type" "application/json"}
                          :body login-body)]
          (is (= 200 (:status login-resp)))
          (when (= 200 (:status login-resp))
            (let [body (json/read-str (:body login-resp) :key-fn keyword)]
              (is (string? (:token body)))
              (is (pos? (:expires-in body)))
              (let [{:keys [valid? claims]} (auth/validate-token (:token body) test-jwt-secret)]
                (is (true? valid?))
                (is (= "apiuser" (:sub claims))))))))))

  (testing "login rejects empty credentials"
    (let [request-body (json/write-str {:username "" :password ""})
          response (test/response-for
                    @service-fn
                    :post "/api/auth/login"
                    :headers {"Content-Type" "application/json"}
                    :body request-body)]
      (is (= 400 (:status response))))))

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
  (testing "deactivates a URL with valid auth"
    (let [token (get-auth-token!)
          create-body (json/write-str {:original-url "https://example.com/deactivate-test"})
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
                                   :delete (str "/api/urls/" short-code)
                                   :headers {"Authorization" (str "Bearer " token)})]
          (is (= 204 (:status deactivate-response)))
          (let [redirect-response (test/response-for 
                                    @service-fn 
                                    :get (str "/r/" short-code))]
            (is (= 410 (:status redirect-response))))))))

  (testing "DELETE without auth returns 401"
    (let [create-body (json/write-str {:original-url "https://example.com/deactivate-noauth"})
          create-response (test/response-for
                           @service-fn
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)
              resp (test/response-for @service-fn :delete (str "/api/urls/" short-code))]
          (is (= 401 (:status resp))))))))

(deftest analytics-endpoint-test
  (testing "returns analytics for a URL with valid auth"
    (let [token (get-auth-token!)
          create-body (json/write-str {:original-url "https://example.com/analytics-test"})
          create-response (test/response-for
                           @service-fn
                           :post "/api/urls"
                           :headers {"Content-Type" "application/json"}
                           :body create-body)]
      (when (= 201 (:status create-response))
        (let [created-url (json/read-str (:body create-response) :key-fn keyword)
              short-code (:short-code created-url)
              analytics-response (test/response-for
                                  @service-fn
                                  :get (str "/api/urls/" short-code "/analytics")
                                  :headers {"Authorization" (str "Bearer " token)})]
          (is (= 200 (:status analytics-response)))
          (when (= 200 (:status analytics-response))
            (let [body (json/read-str (:body analytics-response) :key-fn keyword)]
              (is (= short-code (:short-code body)))
              (is (vector? (:daily-analytics body)))))))))

  (testing "analytics without auth returns 401"
    (let [response (test/response-for
                    @service-fn
                    :get "/api/urls/somecode/analytics")]
      (is (= 401 (:status response))))))

(deftest end-to-end-flow-test
  (testing "complete URL lifecycle"
    (let [token (get-auth-token!)
          create-body (json/write-str {:original-url "https://example.com/e2e-test"})
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

          (let [analytics-resp (test/response-for
                                @service-fn
                                :get (str "/api/urls/" code "/analytics")
                                :headers {"Authorization" (str "Bearer " token)})]
            (is (= 200 (:status analytics-resp)))
            (when (= 200 (:status analytics-resp))
              (let [analytics (json/read-str (:body analytics-resp) :key-fn keyword)]
                (is (= code (:short-code analytics))))))
          
          (let [deactivate-resp (test/response-for
                                 @service-fn
                                 :delete (str "/api/urls/" code)
                                 :headers {"Authorization" (str "Bearer " token)})]
            (is (= 204 (:status deactivate-resp))))
          
          (let [redirect-resp (test/response-for @service-fn :get (str "/r/" code))]
            (is (= 410 (:status redirect-resp)))))))))
