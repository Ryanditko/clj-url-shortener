(ns url-shortener.wire.datomic.url
  (:require [schema.core :as s]))

(s/defschema UrlDatomic
  {:url/id                           s/Uuid
   :url/original-url                 s/Str
   :url/short-code                   s/Str
   :url/created-at                   s/Inst
   :url/clicks                       s/Int
   (s/optional-key :url/expires-at)  s/Inst
   (s/optional-key :url/owner)       s/Str
   (s/optional-key :url/active?)     s/Bool
   (s/optional-key :db/id)           s/Any})

(s/defschema ClickEventDatomic
  {:click/id                         s/Uuid
   :click/short-code                 s/Str
   :click/timestamp                  s/Inst
   (s/optional-key :click/user-agent) s/Str
   (s/optional-key :click/ip-address) s/Str
   (s/optional-key :click/referer)   s/Str
   (s/optional-key :db/id)           s/Any})
