(ns url-shortener.adapters.url
  (:require [schema.core :as s]
            [url-shortener.models.url :as models]
            [url-shortener.wire.in.create-url-request :as wire.in]
            [url-shortener.wire.out.url-response :as wire.out]
            [url-shortener.wire.out.kafka-event :as wire.kafka]
            [url-shortener.wire.cache.url-cache :as wire.cache]
            [url-shortener.wire.datomic.url :as wire.datomic]))

(defn- parse-iso-date [iso-str]
  (when iso-str
    (try
      (java.util.Date/from (java.time.Instant/parse iso-str))
      (catch Exception _ nil))))

(defn- date->iso [^java.util.Date date]
  (when date
    (.format java.time.format.DateTimeFormatter/ISO_INSTANT
             (.toInstant date))))

(s/defn wire-request->model :- models/Url
  [request :- wire.in/CreateUrlRequest
   generated-data :- {:id s/Uuid
                      :short-code s/Str
                      :created-at s/Inst}]
  (let [{:keys [original-url owner expires-at]} request
        {:keys [id short-code created-at]} generated-data
        expires-inst (parse-iso-date expires-at)]
    (cond-> {:id id
             :original-url original-url
             :short-code short-code
             :created-at created-at
             :clicks 0
             :active? true}
      owner (assoc :owner owner)
      expires-inst (assoc :expires-at expires-inst))))

(s/defn model->wire-response :- wire.out/UrlResponse
  [url :- models/Url
   base-url :- s/Str]
  (let [{:keys [id original-url short-code created-at expires-at clicks owner active?]} url
        short-url (str base-url "/" short-code)]
    (cond-> {:id (str id)
             :original-url original-url
             :short-code short-code
             :short-url short-url
             :created-at (date->iso created-at)
             :clicks clicks}
      expires-at (assoc :expires-at (date->iso expires-at))
      owner (assoc :owner owner)
      (some? active?) (assoc :active? active?))))

(s/defn stats->wire-response :- wire.out/UrlStatsResponse
  [stats :- models/UrlStats
   original-url :- s/Str]
  (cond-> {:short-code (:short-code stats)
           :original-url original-url
           :total-clicks (:total-clicks stats)
           :created-at (date->iso (:created-at stats))}
    (:last-accessed stats) (assoc :last-accessed (date->iso (:last-accessed stats)))
    (:unique-visitors stats) (assoc :unique-visitors (:unique-visitors stats))))

(s/defn model->url-created-event :- wire.kafka/UrlCreatedEvent
  [url :- models/Url]
  {:event-id (str (java.util.UUID/randomUUID))
   :event-type "url.created"
   :timestamp (date->iso (java.util.Date.))
   :url-id (str (:id url))
   :short-code (:short-code url)
   :original-url (:original-url url)
   :owner (:owner url)})

(s/defn click-event->kafka-event :- wire.kafka/UrlAccessedEvent
  [click-event :- models/ClickEvent
   original-url :- s/Str]
  {:event-id (str (:event-id click-event))
   :event-type "url.accessed"
   :timestamp (date->iso (:timestamp click-event))
   :short-code (:short-code click-event)
   :original-url original-url
   :user-agent (:user-agent click-event)
   :ip-address (:ip-address click-event)
   :referer (:referer click-event)})

(s/defn deactivation->kafka-event :- wire.kafka/UrlDeactivatedEvent
  [short-code :- s/Str
   reason :- s/Str]
  {:event-id (str (java.util.UUID/randomUUID))
   :event-type "url.deactivated"
   :timestamp (date->iso (java.util.Date.))
   :short-code short-code
   :reason reason})

(s/defn model->cache :- wire.cache/CachedUrl
  [url :- models/Url]
  (cond-> {:short-code (:short-code url)
           :original-url (:original-url url)
           :clicks (:clicks url)
           :created-at (date->iso (:created-at url))}
    (:expires-at url) (assoc :expires-at (date->iso (:expires-at url)))
    (some? (:active? url)) (assoc :active? (:active? url))))

(s/defn cache->model :- models/Url
  [cached :- wire.cache/CachedUrl
   id :- s/Uuid]
  (cond-> {:id id
           :original-url (:original-url cached)
           :short-code (:short-code cached)
           :created-at (parse-iso-date (:created-at cached))
           :clicks (:clicks cached)}
    (:expires-at cached) (assoc :expires-at (parse-iso-date (:expires-at cached)))
    (contains? cached :active?) (assoc :active? (:active? cached))))

(s/defn model->datomic :- wire.datomic/UrlDatomic
  [url :- models/Url]
  (cond-> {:url/id (:id url)
           :url/original-url (:original-url url)
           :url/short-code (:short-code url)
           :url/created-at (:created-at url)
           :url/clicks (:clicks url)}
    (:expires-at url) (assoc :url/expires-at (:expires-at url))
    (:owner url) (assoc :url/owner (:owner url))
    (some? (:active? url)) (assoc :url/active? (:active? url))))

(s/defn datomic->model :- models/Url
  [datomic-url :- wire.datomic/UrlDatomic]
  (cond-> {:id (:url/id datomic-url)
           :original-url (:url/original-url datomic-url)
           :short-code (:url/short-code datomic-url)
           :created-at (:url/created-at datomic-url)
           :clicks (:url/clicks datomic-url 0)}
    (:url/expires-at datomic-url) (assoc :expires-at (:url/expires-at datomic-url))
    (:url/owner datomic-url) (assoc :owner (:url/owner datomic-url))
    (contains? datomic-url :url/active?) (assoc :active? (:url/active? datomic-url))))

(s/defn click-event->datomic :- wire.datomic/ClickEventDatomic
  [click-event :- models/ClickEvent]
  (cond-> {:click/id (:event-id click-event)
           :click/short-code (:short-code click-event)
           :click/timestamp (:timestamp click-event)}
    (:user-agent click-event) (assoc :click/user-agent (:user-agent click-event))
    (:ip-address click-event) (assoc :click/ip-address (:ip-address click-event))
    (:referer click-event) (assoc :click/referer (:referer click-event))))
