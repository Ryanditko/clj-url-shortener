(ns url-shortener.wire.cache.url-cache
  (:require [schema.core :as s]))

(s/defschema CachedUrl
  {:short-code                       s/Str
   :original-url                     s/Str
   :clicks                           s/Int
   :created-at                       s/Str
   (s/optional-key :expires-at)      s/Str
   (s/optional-key :active?)         s/Bool})

(s/defschema CacheStats
  {:short-code                       s/Str
   :total-clicks                     s/Int
   (s/optional-key :last-accessed)   s/Str})
