(ns url-shortener.wire.in.create-url-request
  (:require [schema.core :as s]))

(s/defschema CreateUrlRequest
  {:original-url                     s/Str
   (s/optional-key :custom-code)     s/Str
   (s/optional-key :owner)           s/Str
   (s/optional-key :expires-at)      s/Str
   s/Any                             s/Any})
