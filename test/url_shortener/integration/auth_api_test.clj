(ns url-shortener.integration.auth-api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [clojure.data.json :as json]
            [datomic.api :as d]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.datomic.schema :as schema]
            [url-shortener.diplomat.http-server :as diplomat.http-server]
            [url-shortener.logic.auth :as auth]
            [buddy.sign.jwt :as jwt]))

(def test-uri "datomic:mem://url-shortener-auth-api-test")
(def test-jwt-secret "test-secret-for-auth-integration-tests-min-32!")

(def test-config {:port 0
                  :jwt-secret test-jwt-secret
                  :jwt-ttl-minutes 60
                  :rate-limiter {:api {:max-tokens 100 :refill-per-second 10.0}
                                 :redirect {:max-tokens 100 :refill-per-second 10.0}
                                 :auth {:max-tokens 100 :refill-per-second 10.0}}})

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
          service-map (diplomat.http-server/build-service-map-with-auth components test-config)
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

(defn- register-user! [username password]
  (test/response-for
   @service-fn
   :post "/api/auth/register"
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:username username :password password})))

(defn- login! [username password]
  (test/response-for
   @service-fn
   :post "/api/auth/login"
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:username username :password password})))

(defn- get-token! [username password]
  (register-user! username password)
  (let [resp (login! username password)]
    (when (= 200 (:status resp))
      (:token (json/read-str (:body resp) :key-fn keyword)))))

(deftest register-user-test
  (testing "registers a new user"
    (let [resp (register-user! "newuser" "password123")]
      (is (= 201 (:status resp)))
      (when (= 201 (:status resp))
        (let [body (json/read-str (:body resp) :key-fn keyword)]
          (is (string? (:id body)))
          (is (= "newuser" (:username body)))))))

  (testing "rejects duplicate username"
    (register-user! "dupuser" "password123")
    (let [resp (register-user! "dupuser" "password456")]
      (is (= 400 (:status resp)))))

  (testing "rejects short password"
    (let [resp (register-user! "shortpw" "abc")]
      (is (= 400 (:status resp)))))

  (testing "rejects empty credentials"
    (let [resp (register-user! "" "")]
      (is (= 400 (:status resp))))))

(deftest login-test
  (testing "login with valid credentials returns token"
    (register-user! "loginuser" "validpassword")
    (let [resp (login! "loginuser" "validpassword")]
      (is (= 200 (:status resp)))
      (when (= 200 (:status resp))
        (let [body (json/read-str (:body resp) :key-fn keyword)]
          (is (string? (:token body)))
          (is (pos? (:expires-in body)))
          (let [{:keys [valid? claims]} (auth/validate-token (:token body) test-jwt-secret)]
            (is (true? valid?))
            (is (= "loginuser" (:sub claims))))))))

  (testing "login with wrong password returns 401"
    (register-user! "wrongpw" "correctpassword")
    (let [resp (login! "wrongpw" "incorrectpassword")]
      (is (= 401 (:status resp)))))

  (testing "login with non-existent user returns 401"
    (let [resp (login! "ghostuser" "somepassword")]
      (is (= 401 (:status resp)))))

  (testing "login rejects empty credentials"
    (let [resp (login! "" "")]
      (is (= 400 (:status resp))))))

(deftest auth-protected-delete-test
  (testing "DELETE without auth returns 401"
    (let [create-body (json/write-str {:original-url "https://example.com/auth-delete-test"})
          create-resp (test/response-for
                       @service-fn
                       :post "/api/urls"
                       :headers {"Content-Type" "application/json"}
                       :body create-body)]
      (when (= 201 (:status create-resp))
        (let [url (json/read-str (:body create-resp) :key-fn keyword)
              code (:short-code url)
              delete-resp (test/response-for
                           @service-fn
                           :delete (str "/api/urls/" code))]
          (is (= 401 (:status delete-resp)))))))

  (testing "DELETE with valid auth returns 204"
    (let [token (get-token! "deleter" "password123")
          create-body (json/write-str {:original-url "https://example.com/auth-delete-ok"})
          create-resp (test/response-for
                       @service-fn
                       :post "/api/urls"
                       :headers {"Content-Type" "application/json"}
                       :body create-body)]
      (when (= 201 (:status create-resp))
        (let [url (json/read-str (:body create-resp) :key-fn keyword)
              code (:short-code url)
              delete-resp (test/response-for
                           @service-fn
                           :delete (str "/api/urls/" code)
                           :headers {"Authorization" (str "Bearer " token)})]
          (is (= 204 (:status delete-resp))))))))

(deftest auth-protected-analytics-test
  (testing "analytics without auth returns 401"
    (let [create-body (json/write-str {:original-url "https://example.com/auth-analytics-test"})
          create-resp (test/response-for
                       @service-fn
                       :post "/api/urls"
                       :headers {"Content-Type" "application/json"}
                       :body create-body)]
      (when (= 201 (:status create-resp))
        (let [url (json/read-str (:body create-resp) :key-fn keyword)
              code (:short-code url)
              analytics-resp (test/response-for
                              @service-fn
                              :get (str "/api/urls/" code "/analytics"))]
          (is (= 401 (:status analytics-resp)))))))

  (testing "analytics with valid auth returns 200"
    (let [token (get-token! "analyst" "password123")
          create-body (json/write-str {:original-url "https://example.com/auth-analytics-ok"})
          create-resp (test/response-for
                       @service-fn
                       :post "/api/urls"
                       :headers {"Content-Type" "application/json"}
                       :body create-body)]
      (when (= 201 (:status create-resp))
        (let [url (json/read-str (:body create-resp) :key-fn keyword)
              code (:short-code url)
              analytics-resp (test/response-for
                              @service-fn
                              :get (str "/api/urls/" code "/analytics")
                              :headers {"Authorization" (str "Bearer " token)})]
          (is (= 200 (:status analytics-resp)))
          (when (= 200 (:status analytics-resp))
            (let [body (json/read-str (:body analytics-resp) :key-fn keyword)]
              (is (= code (:short-code body)))
              (is (vector? (:daily-analytics body))))))))))

(deftest auth-with-expired-token-test
  (testing "expired token returns 401"
    (let [expired-token (jwt/sign {:sub "testuser" :iat 1000000 :exp 1000001}
                                  test-jwt-secret
                                  {:alg :hs256})
          response (test/response-for
                    @service-fn
                    :delete "/api/urls/somecode"
                    :headers {"Authorization" (str "Bearer " expired-token)})]
      (is (= 401 (:status response))))))

(deftest rate-limiting-test
  (testing "rate limiter returns 429 when limit exceeded"
    (let [limiter-config {:api {:max-tokens 100 :refill-per-second 10.0}
                          :redirect {:max-tokens 100 :refill-per-second 10.0}
                          :auth {:max-tokens 2 :refill-per-second 0.01}}]
      (d/delete-database "datomic:mem://url-shortener-rate-test")
      (d/create-database "datomic:mem://url-shortener-rate-test")
      (let [conn (d/connect "datomic:mem://url-shortener-rate-test")]
        (schema/migrate! conn)
        (let [datomic (diplomat.datomic/map->Datomic {:uri "datomic:mem://url-shortener-rate-test" :conn conn})
              components {:datomic datomic :cache (->NoOpCache) :producer (->NoOpProducer)}
              config (assoc test-config :rate-limiter limiter-config)
              service-map (diplomat.http-server/build-service-map-with-auth components config)
              svc-fn (::http/service-fn (http/create-servlet service-map))
              body (json/write-str {:username "ratelimited" :password "password123"})]
          (test/response-for svc-fn :post "/api/auth/register"
                             :headers {"Content-Type" "application/json"} :body body)
          (test/response-for svc-fn :post "/api/auth/login"
                             :headers {"Content-Type" "application/json"} :body body)
          (let [resp (test/response-for svc-fn :post "/api/auth/login"
                                        :headers {"Content-Type" "application/json"} :body body)]
            (is (= 429 (:status resp)))
            (is (contains? (:headers resp) "Retry-After")))
          (d/delete-database "datomic:mem://url-shortener-rate-test"))))))

(deftest full-auth-flow-test
  (testing "register -> login -> use token for protected endpoint"
    (let [reg-resp (register-user! "flowuser" "password123")]
      (is (= 201 (:status reg-resp)))
      (let [login-resp (login! "flowuser" "password123")]
        (is (= 200 (:status login-resp)))
        (when (= 200 (:status login-resp))
          (let [token (:token (json/read-str (:body login-resp) :key-fn keyword))
                create-body (json/write-str {:original-url "https://example.com/full-auth-flow"})
                create-resp (test/response-for
                             @service-fn
                             :post "/api/urls"
                             :headers {"Content-Type" "application/json"}
                             :body create-body)]
            (when (= 201 (:status create-resp))
              (let [url (json/read-str (:body create-resp) :key-fn keyword)
                    code (:short-code url)
                    delete-resp (test/response-for
                                 @service-fn
                                 :delete (str "/api/urls/" code)
                                 :headers {"Authorization" (str "Bearer " token)})]
                (is (= 204 (:status delete-resp)))))))))))
