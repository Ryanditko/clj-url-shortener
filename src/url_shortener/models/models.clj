(ns url-shortener.models.models
  (:require [malli.core :as m]
            [url-shortener.models.schemas :as schemas]
            [url-shortener.logic.shortener :as shortener]))

(defn- generate-short-code []
  "Generate an 8-char short code. 
   Replace with a stronger/base62 algorithm if needed."
  (-> (str (java.util.UUID/randomUUID))
      (subs 0 8)))

(defn- parse-iso->date
  "Parses an ISO-8601 string into java.util.Date.
   Returns nil on parse error or nil input."
  [iso-str]
  (when iso-str
    (try
      (java.util.Date/from (java.time.Instant/parse iso-str))
      (catch Exception _ nil))))

(defn- date->iso
  "Formats java.util.Date to ISO-8601 string.
   Returns nil if datetime is nil."
  [^java.util.Date datetime]
  (when datetime
    (.format java.time.format.DateTimeFormatter/ISO_INSTANT
             (.toInstant datetime))))

(defn validate-create-params
  "Validate if the symbol CreateUrlParams can create data.
   Returns {:ok data} when valid, or {:error explain} when invalid."
  [data]
  (if (m/validate schemas/CreateUrlParams data)
    {:ok data}
    {:error (m/explain schemas/CreateUrlParams data)}))

(defn params->domain-url
  "Convert validated CreateUrlParams map to a domain Url map.
   Generates :id (UUID), :short-code (8 chars), and :created-at (inst).
   Converts :expires-at ISO string to inst when provided."
  [params]
  (let [{:keys [original-url owner expires-at]} params
        id (java.util.UUID/randomUUID)
        created-at (java.util.Date.)
        expires-inst (parse-iso->date expires-at)
        short-code (generate-short-code)]
    (cond-> {:id id
             :original-url original-url
             :short-code short-code
             :created-at created-at}
      owner (assoc :owner owner)
      expires-inst (assoc :expires-at expires-inst))))

(defn domain-url->response
  "Convert a domain Url map to a wire-safe response map (strings / primitives).
   Converts UUIDs and instants to strings suitable for JSON."
  [domain-map]
  (let [{:keys [id original-url short-code created-at expires-at clicks owner]} domain-map]
    {:id (when id (.toString ^java.util.UUID id))
     :original-url original-url
     :short-code short-code
     :created-at (date->iso created-at)
     :expires-at (date->iso expires-at)
     :clicks clicks
     :owner owner}))