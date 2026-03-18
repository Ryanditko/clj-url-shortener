(ns url-shortener.controllers.url
  (:require [schema.core :as s]
            [url-shortener.models.url :as models.url]
            [url-shortener.logic.shortener :as logic]
            [url-shortener.adapters.url :as adapters.url]))

(defprotocol IDatomic
  (save-url! [this url-datomic])
  (find-url-by-short-code [this short-code])
  (update-url! [this url-datomic])
  (save-click-event! [this click-event])
  (find-click-events-by-short-code [this short-code]))

(defprotocol ICache
  (cache-url! [this cached-url ttl])
  (get-cached-url [this short-code])
  (invalidate-url! [this short-code]))

(defprotocol IProducer
  (publish-url-created! [this event])
  (publish-url-accessed! [this event])
  (publish-url-deactivated! [this event]))

(s/defn create-url! :- models.url/Url
  [original-url :- s/Str
   {:keys [owner expires-at]} :- {(s/optional-key :owner) s/Str
                                   (s/optional-key :expires-at) s/Str}
   datomic :- IDatomic
   cache :- ICache
   producer :- IProducer]
  (when-not (logic/valid-url? original-url)
    (throw (ex-info "Invalid URL format"
                    {:type :validation-error
                     :field :original-url
                     :value original-url})))

  (let [id (java.util.UUID/randomUUID)
        timestamp (System/currentTimeMillis)
        short-code (logic/generate-short-code-from-timestamp timestamp 8)
        created-at (java.util.Date.)
        url (adapters.url/wire-request->model
             {:original-url original-url
              :owner owner
              :expires-at expires-at}
             {:id id
              :short-code short-code
              :created-at created-at})
        url-datomic (adapters.url/model->datomic url)]

    (save-url! datomic url-datomic)
    (cache-url! cache (adapters.url/model->cache url) 3600)
    (publish-url-created! producer (adapters.url/model->url-created-event url))
    url))

(s/defn redirect-url! :- models.url/Url
  [short-code :- s/Str
   request-metadata :- {(s/optional-key :user-agent) (s/maybe s/Str)
                        (s/optional-key :ip-address) (s/maybe s/Str)
                        (s/optional-key :referer) (s/maybe s/Str)}
   datomic :- IDatomic
   cache :- ICache
   producer :- IProducer]
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

    (let [updated-url (logic/increment-clicks url)
          click-event {:event-id (java.util.UUID/randomUUID)
                       :short-code short-code
                       :timestamp (java.util.Date.)
                       :user-agent (:user-agent request-metadata)
                       :ip-address (:ip-address request-metadata)
                       :referer (:referer request-metadata)}]
      (update-url! datomic (adapters.url/model->datomic updated-url))
      (save-click-event! datomic (adapters.url/click-event->datomic click-event))
      (cache-url! cache (adapters.url/model->cache updated-url) 3600)
      (publish-url-accessed! producer
                             (adapters.url/click-event->kafka-event click-event (:original-url url)))
      updated-url)))

(defn get-url-stats
  [short-code datomic]
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
      {:stats stats :original-url (:original-url url)})))

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
