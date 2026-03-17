(ns url-shortener.wire.out.kafka-event
  (:require [schema.core :as s]))

(s/defschema UrlCreatedEvent
  {:event-id                         s/Str
   :event-type                       (s/eq "url.created")
   :timestamp                        s/Str
   :url-id                           s/Str
   :short-code                       s/Str
   :original-url                     s/Str
   (s/optional-key :owner)           s/Str})

(s/defschema UrlAccessedEvent
  {:event-id                         s/Str
   :event-type                       (s/eq "url.accessed")
   :timestamp                        s/Str
   :short-code                       s/Str
   :original-url                     s/Str
   (s/optional-key :user-agent)      s/Str
   (s/optional-key :ip-address)      s/Str
   (s/optional-key :referer)         s/Str})

(s/defschema UrlDeactivatedEvent
  {:event-id                         s/Str
   :event-type                       (s/eq "url.deactivated")
   :timestamp                        s/Str
   :short-code                       s/Str
   :reason                           (s/enum "user-requested" "expired" "violation")})
