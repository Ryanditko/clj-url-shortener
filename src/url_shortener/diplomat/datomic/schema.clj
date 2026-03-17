(ns url-shortener.diplomat.datomic.schema
  (:require [datomic.api :as d]))

(def url-schema
  [{:db/ident :url/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for URL"}
   
   {:db/ident :url/original-url
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Original long URL"}
   
   {:db/ident :url/short-code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/value
    :db/index true
    :db/doc "Short code for URL (Base62 encoded)"}
   
   {:db/ident :url/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when URL was created"}
   
   {:db/ident :url/clicks
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of times URL was accessed"}
   
   {:db/ident :url/expires-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Expiration timestamp (optional)"}
   
   {:db/ident :url/owner
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Owner identifier (optional)"}
   
   {:db/ident :url/active?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether URL is active (for soft delete)"}])

(def click-event-schema
  [{:db/ident :click/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for click event"}
   
   {:db/ident :click/short-code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Reference to URL short code"}
   
   {:db/ident :click/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when click occurred"}
   
   {:db/ident :click/user-agent
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User agent string (optional)"}
   
   {:db/ident :click/ip-address
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "IP address of client (optional)"}
   
   {:db/ident :click/referer
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "HTTP referer (optional)"}])

(def schema (concat url-schema click-event-schema))

(defn migrate! [conn]
  @(d/transact conn schema))
