(ns url-shortener.diplomat.http-server
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :as interceptor]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [url-shortener.controllers.url :as controllers]
            [url-shortener.adapters.url :as adapters]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.cache :as diplomat.cache]
            [url-shortener.diplomat.producer :as diplomat.producer]
            [url-shortener.logic.auth :as auth]
            [url-shortener.logic.rate-limiter :as rate-limiter]
            [url-shortener.observability.metrics :as metrics]))

(defn- ->datomic-adapter [datomic]
  (reify controllers/IDatomic
    (save-url! [_ url] (diplomat.datomic/save-url! datomic url))
    (find-url-by-short-code [_ code] (diplomat.datomic/find-url-by-short-code datomic code))
    (update-url! [_ url] (diplomat.datomic/update-url! datomic url))
    (save-click-event! [_ event] (diplomat.datomic/save-click-event! datomic event))
    (find-click-events-by-short-code [_ code] (diplomat.datomic/find-click-events-by-short-code datomic code))))

(defn- ->cache-adapter [cache]
  (reify controllers/ICache
    (cache-url! [_ cached-url ttl] (diplomat.cache/set-url! cache cached-url ttl))
    (get-cached-url [_ code] (diplomat.cache/get-url cache code))
    (invalidate-url! [_ code] (diplomat.cache/delete-url! cache code))
    (cache-stats! [_ stats ttl] (diplomat.cache/set-stats! cache stats ttl))
    (get-cached-stats [_ code] (diplomat.cache/get-stats cache code))))

(defn- ->producer-adapter [producer]
  (reify controllers/IProducer
    (publish-url-created! [_ event] (diplomat.producer/publish-url-created! producer event))
    (publish-url-accessed! [_ event] (diplomat.producer/publish-url-accessed! producer event))
    (publish-url-deactivated! [_ event] (diplomat.producer/publish-url-deactivated! producer event))))

(defn- inject-components [components config]
  (interceptor/interceptor
   {:name ::inject-components
    :enter (fn [context]
             (let [{:keys [datomic cache producer]} components]
               (assoc-in context [:request :components]
                         {:datomic (->datomic-adapter datomic)
                          :raw-datomic datomic
                          :cache (->cache-adapter cache)
                          :producer (->producer-adapter producer)
                          :base-url (:base-url config "http://localhost:8080/r")
                          :jwt-secret (:jwt-secret config)
                          :jwt-ttl-minutes (:jwt-ttl-minutes config 60)
                          :rate-limiter (:rate-limiter components)})))}))

(defn- error-interceptor []
  (interceptor/interceptor
   {:name ::error-handler
    :error (fn [context exception]
             (let [cause (if (instance? clojure.lang.ExceptionInfo exception)
                           exception
                           (ex-cause exception))
                   ex-data (when (instance? clojure.lang.ExceptionInfo cause)
                             (ex-data cause))
                   error-type (:type ex-data)
                   response (case error-type
                              :validation-error
                              {:status 400
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "Validation error"
                                                       :message (if cause (.getMessage cause) (.getMessage exception))})}
                              :not-found
                              {:status 404
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "Not found"
                                                       :message (if cause (.getMessage cause) (.getMessage exception))})}
                              :expired-url
                              {:status 410
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "URL expired"
                                                       :message (if cause (.getMessage cause) (.getMessage exception))})}
                              :unauthorized
                              {:status 401
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "Unauthorized"
                                                       :message (if cause (.getMessage cause) (.getMessage exception))})}
                              :rate-limited
                              {:status 429
                               :headers {"Content-Type" "application/json"
                                         "Retry-After" (str (:retry-after ex-data 60))}
                               :body (json/write-str {:error "Too many requests"
                                                       :message (if cause (.getMessage cause) (.getMessage exception))})}
                              {:status 500
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "Internal server error"
                                                       :message "An unexpected error occurred"})})]
               (log/error exception "Request error")
               (assoc context :response response)))}))

;; --- Auth interceptor ---

(defn- require-auth []
  (interceptor/interceptor
   {:name ::require-auth
    :enter (fn [context]
             (let [request (:request context)
                   auth-header (get-in request [:headers "authorization"])
                   token (auth/extract-bearer-token auth-header)
                   jwt-secret (get-in request [:components :jwt-secret])]
               (if-not token
                 (throw (ex-info "Missing or invalid authorization header"
                                 {:type :unauthorized}))
                 (let [result (auth/validate-token token jwt-secret)]
                   (if (:valid? result)
                     (assoc-in context [:request :authenticated-user] (:claims result))
                     (throw (ex-info (case (:error result)
                                       :token-expired "Token has expired"
                                       "Invalid token")
                                     {:type :unauthorized})))))))}))

;; --- Rate-limiting interceptor ---

(defn- rate-limit [limits-key]
  (interceptor/interceptor
   {:name ::rate-limit
    :enter (fn [context]
             (let [request (:request context)
                   limiter (get-in request [:components :rate-limiter])
                   ip (or (get-in request [:headers "x-forwarded-for"])
                          (:remote-addr request)
                          "unknown")]
               (if-not limiter
                 context
                 (let [result (rate-limiter/check-rate! limiter ip limits-key)]
                   (if (:allowed? result)
                     context
                     (throw (ex-info "Rate limit exceeded"
                                     {:type :rate-limited
                                      :retry-after (:retry-after result)})))))))}))

;; --- Metrics interceptor ---

(defn- metrics-interceptor []
  (interceptor/interceptor
   {:name ::metrics
    :enter (fn [context]
             (assoc-in context [:request ::request-start] (System/nanoTime)))
    :leave (fn [context]
             (let [start (get-in context [:request ::request-start])
                   duration-s (when start (/ (- (System/nanoTime) start) 1e9))
                   request (:request context)
                   method (name (:request-method request))
                   path (or (:path-info request) (:uri request) "unknown")
                   status (str (get-in context [:response :status] 0))]
               (when start
                 (metrics/inc-request-count method path status)
                 (metrics/observe-request-duration method path status duration-s)))
             context)}))

;; --- Handlers ---

(defn create-url-handler [request]
  (let [{:keys [json-params components]} request
        {:keys [original-url owner expires-at custom-code]} json-params
        {:keys [datomic cache producer]} components
        url (controllers/create-url! original-url
                                     {:owner owner :expires-at expires-at :custom-code custom-code}
                                     datomic cache producer)]
    (metrics/inc-urls-created)
    {:status 201
     :headers {"Content-Type" "application/json"}
     :body (json/write-str (adapters/model->wire-response url (:base-url components)))}))

(defn redirect-url-handler [request]
  (let [{:keys [path-params components headers]} request
        short-code (:code path-params)
        metadata {:user-agent (get headers "user-agent")
                  :ip-address (or (get headers "x-forwarded-for") (:remote-addr request))
                  :referer (get headers "referer")}
        {:keys [datomic cache producer]} components
        url (controllers/redirect-url! short-code metadata datomic cache producer)]
    (metrics/inc-redirects)
    (future
      (try
        (controllers/track-click! short-code metadata (:original-url url) datomic producer)
        (catch Exception e
          (log/warn "Async click tracking failed" {:error (.getMessage e)}))))
    {:status 302
     :headers {"Location" (:original-url url)}}))

(defn get-stats-handler [request]
  (let [{:keys [path-params components]} request
        short-code (:code path-params)
        {:keys [datomic cache]} components
        response (controllers/get-url-stats short-code datomic cache)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str response)}))

(defn deactivate-url-handler [request]
  (let [{:keys [path-params components]} request
        short-code (:code path-params)
        {:keys [datomic cache producer]} components]
    (controllers/deactivate-url! short-code datomic cache producer)
    {:status 204 :body ""}))

(defn health-handler [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:status "ok"})})

(defn metrics-handler [_]
  {:status 200
   :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
   :body (metrics/export-metrics)})

(defn register-handler [request]
  (let [{:keys [json-params]} request
        {:keys [username password]} json-params
        raw-datomic (get-in request [:components :raw-datomic])]
    (when (or (empty? username) (empty? password))
      (throw (ex-info "Username and password are required"
                      {:type :validation-error})))
    (when (< (count password) 8)
      (throw (ex-info "Password must be at least 8 characters"
                      {:type :validation-error})))
    (when (diplomat.datomic/find-user-by-username raw-datomic username)
      (throw (ex-info "Username already exists"
                      {:type :validation-error})))
    (let [user-id (java.util.UUID/randomUUID)
          password-hash (auth/hash-password password)]
      (diplomat.datomic/save-user! raw-datomic
                                   {:user/id user-id
                                    :user/username username
                                    :user/password-hash password-hash
                                    :user/created-at (java.util.Date.)})
      {:status 201
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:id (str user-id) :username username})})))

(defn login-handler [request]
  (let [{:keys [json-params components]} request
        {:keys [username password]} json-params
        jwt-secret (:jwt-secret components)
        ttl-minutes (:jwt-ttl-minutes components)
        raw-datomic (get-in request [:components :raw-datomic])]
    (when (or (empty? username) (empty? password))
      (throw (ex-info "Username and password are required"
                      {:type :validation-error})))
    (let [user (diplomat.datomic/find-user-by-username raw-datomic username)]
      (when-not (and user (auth/check-password password (:user/password-hash user)))
        (throw (ex-info "Invalid username or password"
                        {:type :unauthorized})))
      (let [token (auth/generate-token username jwt-secret ttl-minutes)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:token token :expires-in (* ttl-minutes 60)})}))))

(defn get-analytics-handler [request]
  (let [{:keys [path-params]} request
        short-code (:code path-params)
        raw-datomic (get-in request [:components :raw-datomic])
        url-exists? (diplomat.datomic/find-url-by-short-code raw-datomic short-code)]
    (when-not url-exists?
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))
    (let [analytics (diplomat.datomic/find-all-daily-analytics raw-datomic short-code)
          response (map (fn [a]
                          {:date (when (:analytics/date a)
                                  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                                           (.toInstant ^java.util.Date (:analytics/date a))))
                           :clicks (:analytics/clicks a 0)
                           :unique-visitors (:analytics/unique-visitors a 0)})
                        analytics)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:short-code short-code
                               :daily-analytics (vec response)})})))

(def routes
  (route/expand-routes
   #{["/health" :get [health-handler] :route-name ::health]
     ["/metrics" :get [metrics-handler] :route-name ::metrics]
     ["/api/auth/register" :post [(body-params/body-params) register-handler] :route-name ::register]
     ["/api/auth/login" :post [(body-params/body-params) login-handler] :route-name ::login]
     ["/api/urls" :post [(body-params/body-params) create-url-handler] :route-name ::create-url]
     ["/api/urls/:code/stats" :get [get-stats-handler] :route-name ::stats]
     ["/api/urls/:code/analytics" :get [get-analytics-handler] :route-name ::analytics]
     ["/api/urls/:code" :delete [deactivate-url-handler] :route-name ::deactivate]
     ["/r/:code" :get [redirect-url-handler] :route-name ::redirect]}))

(defn- add-cors-headers [context]
  (let [origin (get-in context [:request :headers "origin"])]
    (if origin
      (update-in context [:response :headers] merge
                 {"Access-Control-Allow-Origin" origin
                  "Access-Control-Allow-Credentials" "true"
                  "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                  "Access-Control-Allow-Headers" "Authorization, Content-Type, Accept"})
      context)))

(defn- cors-leave-interceptor []
  (interceptor/interceptor
   {:name ::cors-leave
    :leave add-cors-headers}))

(defn base-service-map [config]
  {::http/routes routes
   ::http/type :jetty
   ::http/port (:port config 8080)
   ::http/join? false})

(defn build-service-map [components config]
  (-> (base-service-map config)
      http/default-interceptors
      (update ::http/interceptors
              #(into [(error-interceptor)
                      (cors-leave-interceptor)
                      (metrics-interceptor)
                      (inject-components components config)]
                     %))))

(defn build-service-map-with-auth [components config]
  (let [limiter (rate-limiter/create-limiter (:rate-limiter config))
        components-with-limiter (assoc components :rate-limiter limiter)]
    (-> (base-service-map config)
        (assoc ::http/routes
               (route/expand-routes
                #{["/health" :get [health-handler] :route-name ::health]
                  ["/metrics" :get [metrics-handler] :route-name ::metrics]
                  ["/api/auth/register" :post [(rate-limit :auth)
                                               (body-params/body-params)
                                               register-handler]
                   :route-name ::register]
                  ["/api/auth/login" :post [(rate-limit :auth)
                                            (body-params/body-params)
                                            login-handler]
                   :route-name ::login]
                  ["/api/urls" :post [(rate-limit :api)
                                      (body-params/body-params)
                                      create-url-handler]
                   :route-name ::create-url]
                  ["/api/urls/:code/stats" :get [(rate-limit :api)
                                                  get-stats-handler]
                   :route-name ::stats]
                  ["/api/urls/:code/analytics" :get [(rate-limit :api)
                                                      (require-auth)
                                                      get-analytics-handler]
                   :route-name ::analytics]
                  ["/api/urls/:code" :delete [(rate-limit :api)
                                              (require-auth)
                                              deactivate-url-handler]
                   :route-name ::deactivate]
                  ["/r/:code" :get [(rate-limit :redirect)
                                    redirect-url-handler]
                   :route-name ::redirect]}))
        http/default-interceptors
        (update ::http/interceptors
                #(into [(error-interceptor)
                        (cors-leave-interceptor)
                        (metrics-interceptor)
                        (inject-components components-with-limiter config)]
                       %)))))

(defrecord HttpServer [config datomic cache producer server]
  component/Lifecycle

  (start [this]
    (if server
      this
      (do
        (metrics/init-metrics!)
        (let [components {:datomic datomic :cache cache :producer producer}
              service-map (build-service-map-with-auth components config)
              started-server (http/start (http/create-server service-map))]
          (log/info "HTTP server started on port" (:port config 8080))
          (assoc this :server started-server)))))

  (stop [this]
    (when server
      (http/stop server))
    (assoc this :server nil)))

(defn new-http-server [config]
  (map->HttpServer {:config config}))
