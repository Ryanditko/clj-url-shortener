(ns url-shortener.wire.out.url-response
  (:require [schema.core :as s]))

(s/defschema UrlResponse
  {:id                               s/Str
   :original-url                     s/Str
   :short-code                       s/Str
   :short-url                        s/Str
   :created-at                       s/Str
   :clicks                           s/Int
   (s/optional-key :expires-at)      s/Str
   (s/optional-key :owner)           s/Str
   (s/optional-key :active?)         s/Bool})

(s/defschema UrlStatsResponse
  {:short-code                       s/Str
   :original-url                     s/Str
   :total-clicks                     s/Int
   :created-at                       s/Str
   (s/optional-key :last-accessed)   s/Str
   (s/optional-key :unique-visitors) s/Int})

(s/defschema RedirectResponse
  {:status                           (s/enum 302 404 410)
   :headers                          {s/Str s/Str}
   (s/optional-key :body)            s/Str})
