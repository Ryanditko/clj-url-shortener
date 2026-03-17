(ns url-shortener.models.url
  (:require [schema.core :as s]))

(s/defschema Url
  {:id                               s/Uuid
   :original-url                     s/Str
   :short-code                       s/Str
   :created-at                       s/Inst
   :clicks                           s/Int
   (s/optional-key :expires-at)      s/Inst
   (s/optional-key :owner)           s/Str
   (s/optional-key :active?)         s/Bool})

(s/defschema UrlStats
  {:short-code                       s/Str
   :total-clicks                     s/Int
   :created-at                       s/Inst
   (s/optional-key :last-accessed)   s/Inst
   (s/optional-key :unique-visitors) s/Int})

(s/defschema ClickEvent
  {:event-id                         s/Uuid
   :short-code                       s/Str
   :timestamp                        s/Inst
   (s/optional-key :user-agent)      s/Str
   (s/optional-key :ip-address)      s/Str
   (s/optional-key :referer)         s/Str})
