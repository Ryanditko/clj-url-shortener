(ns url-shortener.controllers.url
  (:require [schema.core :as s]
            [url-shortener.models.url :as models.url]
            [url-shortener.logic.shortener :as logic]
            [url-shortener.adapters.url :as adapters.url]))

(defprotocol IDatomic
  (save-url! [this url-datomic])
  (find-url-by-short-code [this short-code])
  (update-url! [this url-datomic]))

(defprotocol IProducer
  (publish-url-created! [this url])
  (publish-url-accessed! [this url]))

(s/defn create-url! :- models.url/Url
  [original-url :- s/Str
   {:keys [owner expires-at]} :- {(s/optional-key :owner) s/Str
                                   (s/optional-key :expires-at) s/Str}
   datomic :- IDatomic
   producer :- IProducer]
  (when-not (logic/valid-url? original-url)
    (throw (ex-info "Invalid URL format" 
                    {:type :validation-error
                     :field :original-url
                     :value original-url})))
  
  (let [id (java.util.UUID/randomUUID)
        timestamp (System/currentTimeMillis)
        short-code (logic/generate-short-code-from-timestamp timestamp 8)
        created-at (java.util.Date.)
        url (adapters.url/wire-request->model 
             {:original-url original-url
              :owner owner
              :expires-at expires-at}
             {:id id
              :short-code short-code
              :created-at created-at})
        url-datomic (adapters.url/model->datomic url)]
    
    (save-url! datomic url-datomic)
    (publish-url-created! producer url)
    url))

(s/defn redirect-url! :- models.url/Url
  [short-code :- s/Str
   datomic :- IDatomic
   producer :- IProducer]
  (if-let [url-datomic (find-url-by-short-code datomic short-code)]
    (let [url (adapters.url/datomic->model url-datomic)]
      (when (logic/url-expired? url (java.util.Date.))
        (throw (ex-info "URL has expired"
                        {:type :expired-url
                         :short-code short-code})))
      
      (let [updated-url (logic/increment-clicks url)
            updated-datomic (adapters.url/model->datomic updated-url)]
        (update-url! datomic updated-datomic)
        (publish-url-accessed! producer updated-url)
        updated-url))
    
    (throw (ex-info "Short code not found"
                    {:type :not-found
                     :short-code short-code}))))
