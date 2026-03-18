(ns url-shortener.controllers.url
  (:require [schema.core :as s]
            [url-shortener.models.url :as models.url]
            [url-shortener.logic.shortener :as logic]
            [url-shortener.adapters.url :as adapters.url]
            [clojure.tools.logging :as log]))

(defprotocol IDatomic
  (save-url! [this url-datomic])
  (find-url-by-short-code [this short-code])
  (update-url! [this url-datomic])
  (save-click-event! [this click-event])
  (find-click-events-by-short-code [this short-code]))

(defprotocol ICache
  (cache-url! [this cached-url ttl])
  (get-cached-url [this short-code])
  (invalidate-url! [this short-code])
  (cache-stats! [this stats ttl])
  (get-cached-stats [this short-code]))

(defprotocol IProducer
  (publish-url-created! [this event])
  (publish-url-accessed! [this event])
  (publish-url-deactivated! [this event]))

(def ^:private max-collision-retries 3)

(defn- try-save-url! [datomic url-datomic]
  (try
    (save-url! datomic url-datomic)
    true
    (catch Exception e
      (if (some-> (ex-data e) :db/error (= :db.error/unique-conflict))
        false
        (throw e)))))

(s/defn create-url! :- models.url/Url
  [original-url :- s/Str
   {:keys [owner expires-at custom-code]} :- {(s/optional-key :owner) s/Str
                                               (s/optional-key :expires-at) s/Str
                                               (s/optional-key :custom-code) s/Str}
   datomic :- IDatomic
   cache :- ICache
   producer :- IProducer]
  (when-not (logic/valid-url? original-url)
    (throw (ex-info "Invalid URL format"
                    {:type :validation-error
                     :field :original-url
                     :value original-url})))

  (when (and custom-code (not (logic/valid-custom-code? custom-code)))
    (throw (ex-info "Invalid custom code format"
                    {:type :validation-error
                     :field :custom-code
                     :value custom-code})))

  (when (and custom-code (find-url-by-short-code datomic custom-code))
    (throw (ex-info "Custom code already in use"
                    {:type :validation-error
                     :field :custom-code
                     :value custom-code})))

  (let [id (java.util.UUID/randomUUID)
        created-at (java.util.Date.)]
    (if custom-code
      (let [url (adapters.url/wire-request->model
                 {:original-url original-url :owner owner :expires-at expires-at}
                 {:id id :short-code custom-code :created-at created-at})
            url-datomic (adapters.url/model->datomic url)]
        (try-save-url! datomic url-datomic)
        (cache-url! cache (adapters.url/model->cache url) 3600)
        (publish-url-created! producer (adapters.url/model->url-created-event url))
        url)
      (loop [attempt 0
             short-code (logic/generate-short-code-from-timestamp (System/currentTimeMillis) 8)]
        (let [url (adapters.url/wire-request->model
                   {:original-url original-url :owner owner :expires-at expires-at}
                   {:id id :short-code short-code :created-at created-at})
              url-datomic (adapters.url/model->datomic url)]
          (if (try-save-url! datomic url-datomic)
            (do
              (cache-url! cache (adapters.url/model->cache url) 3600)
              (publish-url-created! producer (adapters.url/model->url-created-event url))
              url)
            (if (< attempt max-collision-retries)
              (recur (inc attempt) (logic/generate-alternative-code short-code))
              (throw (ex-info "Failed to generate unique short code"
                              {:type :validation-error :attempts (inc attempt)})))))))))

(s/defn redirect-url! :- models.url/Url
  [short-code :- s/Str
   _request-metadata
   datomic :- IDatomic
   cache :- ICache
   _producer :- IProducer]
  (let [cached (get-cached-url cache short-code)
        url-datomic (when-not cached (find-url-by-short-code datomic short-code))
        url (cond
              cached (adapters.url/cache->model cached (java.util.UUID/randomUUID))
              url-datomic (adapters.url/datomic->model url-datomic)
              :else nil)]

    (when-not url
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))

    (when (false? (:active? url))
      (throw (ex-info "URL has been deactivated"
                      {:type :expired-url :short-code short-code})))

    (when (logic/url-expired? url (java.util.Date.))
      (throw (ex-info "URL has expired"
                      {:type :expired-url :short-code short-code})))

    url))

(defn track-click!
  [short-code request-metadata original-url datomic producer]
  (let [click-event {:event-id (java.util.UUID/randomUUID)
                     :short-code short-code
                     :timestamp (java.util.Date.)
                     :user-agent (:user-agent request-metadata)
                     :ip-address (:ip-address request-metadata)
                     :referer (:referer request-metadata)}]
    (save-click-event! datomic (adapters.url/click-event->datomic click-event))
    (publish-url-accessed! producer
                           (adapters.url/click-event->kafka-event click-event original-url))))

(defn get-url-stats
  [short-code datomic cache]
  (if-let [cached (get-cached-stats cache short-code)]
    {:stats cached :original-url nil :cached? true}
    (let [url-datomic (find-url-by-short-code datomic short-code)]
      (when-not url-datomic
        (throw (ex-info "Short code not found"
                        {:type :not-found :short-code short-code})))
      (let [url (adapters.url/datomic->model url-datomic)
            click-events-datomic (find-click-events-by-short-code datomic short-code)
            click-events (map (fn [ce]
                                {:event-id (:click/id ce)
                                 :short-code (:click/short-code ce)
                                 :timestamp (:click/timestamp ce)
                                 :ip-address (:click/ip-address ce)})
                              click-events-datomic)
            stats (logic/calculate-stats url click-events)]
        {:stats stats :original-url (:original-url url) :cached? false}))))

(defn deactivate-url!
  [short-code datomic cache producer]
  (let [url-datomic (find-url-by-short-code datomic short-code)]
    (when-not url-datomic
      (throw (ex-info "Short code not found"
                      {:type :not-found :short-code short-code})))
    (let [url (adapters.url/datomic->model url-datomic)
          deactivated (assoc url :active? false)]
      (update-url! datomic (adapters.url/model->datomic deactivated))
      (invalidate-url! cache short-code)
      (publish-url-deactivated! producer
                                (adapters.url/deactivation->kafka-event short-code "user-requested")))))
