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
            [url-shortener.diplomat.producer :as diplomat.producer]))

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
                          :cache (->cache-adapter cache)
                          :producer (->producer-adapter producer)
                          :base-url (:base-url config "http://localhost:8080/r")})))}))

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
                              {:status 500
                               :headers {"Content-Type" "application/json"}
                               :body (json/write-str {:error "Internal server error"
                                                       :message "An unexpected error occurred"})})]
               (log/error exception "Request error")
               (assoc context :response response)))}))

(defn create-url-handler [request]
  (let [{:keys [json-params components]} request
        {:keys [original-url owner expires-at custom-code]} json-params
        {:keys [datomic cache producer]} components
        url (controllers/create-url! original-url
                                     {:owner owner :expires-at expires-at :custom-code custom-code}
                                     datomic cache producer)]
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

(def routes
  (route/expand-routes
   #{["/health" :get [health-handler] :route-name ::health]
     ["/api/urls" :post [(body-params/body-params) create-url-handler] :route-name ::create-url]
     ["/api/urls/:code/stats" :get [get-stats-handler] :route-name ::stats]
     ["/api/urls/:code" :delete [deactivate-url-handler] :route-name ::deactivate]
     ["/r/:code" :get [redirect-url-handler] :route-name ::redirect]}))

(defn base-service-map [config]
  {::http/routes routes
   ::http/type :jetty
   ::http/port (:port config 8080)
   ::http/join? false})

(defn build-service-map [components config]
  (-> (base-service-map config)
      http/default-interceptors
      (update ::http/interceptors
              #(into [(error-interceptor) (inject-components components config)] %))))

(defrecord HttpServer [config datomic cache producer server]
  component/Lifecycle

  (start [this]
    (if server
      this
      (let [components {:datomic datomic :cache cache :producer producer}
            service-map (build-service-map components config)
            started-server (http/start (http/create-server service-map))]
        (log/info "HTTP server started on port" (:port config 8080))
        (assoc this :server started-server))))

  (stop [this]
    (when server
      (http/stop server))
    (assoc this :server nil)))

(defn new-http-server [config]
  (map->HttpServer {:config config}))
