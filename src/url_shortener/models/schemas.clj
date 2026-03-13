(ns url-shortener.models.schemas
  (:require [malli.core :as m]))

; global symbols fragments
(def id uuid?)
(def original-url string?)
(def short-code string?)
(def timestamp inst?)
(def click-count int?)

;; initial schemas
(def CreateUrlParams
  [:map
   [:original-url string?]
   [:optional-key :owner string?]
   [:optional-key :expires-at string?]])

(def Url
  [:map
   [:id uuid?]
   [:original-url string?]
   [:short-code string?]
   [:created-at inst?]
   [:optional-key :expires-at inst?]
   [:optional-key :clicks int?]
   [:optional-key :owner string?]])