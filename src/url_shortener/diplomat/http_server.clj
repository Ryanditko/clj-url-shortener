(ns url-shortener.diplomat.http-server
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :as interceptor]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [url-shortener.adapters.url :as adapters]
            [url-shortener.logic.shortener :as logic]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.cache :as diplomat.cache]
            [url-shortener.diplomat.producer :as diplomat.producer]))

(defn- inject-components [components config]
  (interceptor/interceptor
   {:name ::inject-components
    :enter (fn [context]
             (assoc-in context [:request :components]
                       (assoc components :base-url (:base-url config "http://localhost:8080/r"))))}))

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
        {:keys [datomic cache producer]} components
        {:keys [original-url owner expires-at]} json-params]

    (when-not (logic/valid-url? original-url)
      (throw (ex-info "Invalid URL format"
                      {:type :validation-error
                       :field :original-url})))

    (let [id (java.util.UUID/randomUUID)
          timestamp (System/currentTimeMillis)
          short-code (logic/generate-short-code-from-timestamp timestamp 8)
          created-at (java.util.Date.)

          url (adapters/wire-request->model
               {:original-url original-url :owner owner :expires-at expires-at}
               {:id id :short-code short-code :created-at created-at})

          url-datomic (adapters/model->datomic url)]

      (diplomat.datomic/save-url! datomic url-datomic)
      (diplomat.cache/set-url! cache (adapters/model->cache url) 3600)
      (diplomat.producer/publish-url-created! producer (adapters/model->url-created-event url))

      {:status 201
       :headers {"Content-Type" "application/json"}
       :body (json/write-str (adapters/model->wire-response url (:base-url components)))})))

(defn redirect-url-handler [request]
  (let [{:keys [path-params components]} request
        {:keys [datomic cache producer]} components
        short-code (:code path-params)

        cached (diplomat.cache/get-url cache short-code)
        url-datomic (when-not cached
                      (diplomat.datomic/find-url-by-short-code datomic short-code))
        url (cond
              cached (adapters/cache->model cached (java.util.UUID/randomUUID))
              url-datomic (adapters/datomic->model url-datomic)
              :else nil)]

    (when-not url
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))

    (let [redirect-response (adapters/model->redirect-response url (java.util.Date.))]
      (when (= 302 (:status redirect-response))
        (let [updated-url (logic/increment-clicks url)
              click-event {:event-id (java.util.UUID/randomUUID)
                           :short-code short-code
                           :timestamp (java.util.Date.)}]
          (diplomat.datomic/update-url! datomic (adapters/model->datomic updated-url))
          (diplomat.datomic/save-click-event! datomic (adapters/click-event->datomic click-event))
          (diplomat.cache/set-url! cache (adapters/model->cache updated-url) 3600)
          (diplomat.producer/publish-url-accessed!
           producer
           (adapters/click-event->kafka-event click-event (:original-url url)))))

      redirect-response)))

(defn get-stats-handler [request]
  (let [{:keys [path-params components]} request
        {:keys [datomic cache]} components
        short-code (:code path-params)
        url-datomic (diplomat.datomic/find-url-by-short-code datomic short-code)]

    (when-not url-datomic
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))

    (let [url (adapters/datomic->model url-datomic)
          click-events-datomic (diplomat.datomic/find-click-events-by-short-code datomic short-code)
          click-events (map #(hash-map :event-id (:click/id %)
                                       :short-code (:click/short-code %)
                                       :timestamp (:click/timestamp %))
                            click-events-datomic)
          stats (logic/calculate-stats url click-events)
          response (adapters/stats->wire-response stats (:original-url url))]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str response)})))

(defn deactivate-url-handler [request]
  (let [{:keys [path-params components]} request
        {:keys [datomic cache producer]} components
        short-code (:code path-params)
        url-datomic (diplomat.datomic/find-url-by-short-code datomic short-code)]

    (when-not url-datomic
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))

    (let [url (adapters/datomic->model url-datomic)
          deactivated (assoc url :active? false)]
      (diplomat.datomic/update-url! datomic (adapters/model->datomic deactivated))
      (diplomat.cache/delete-url! cache short-code)
      (diplomat.producer/publish-url-deactivated!
       producer
       (adapters/deactivation->kafka-event short-code "user-requested"))

      {:status 204 :body ""})))

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
