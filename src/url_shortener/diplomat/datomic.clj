(ns url-shortener.diplomat.datomic
  (:require [schema.core :as s]
            [datomic.api :as d]
            [com.stuartsierra.component :as component]
            [url-shortener.diplomat.datomic.schema :as schema]
            [url-shortener.wire.datomic.url :as wire.datomic]
            [clojure.tools.logging :as log]))

(defrecord Datomic [uri conn]
  component/Lifecycle
  
  (start [this]
    (if conn
      this
      (do
        (log/info "Starting Datomic connection" {:uri uri})
        (d/create-database uri)
        (let [connection (d/connect uri)]
          (log/info "Datomic connection established, running schema migration")
          (schema/migrate! connection)
          (log/info "Schema migration completed")
          (assoc this :conn connection)))))
  
  (stop [this]
    (when conn
      (log/info "Stopping Datomic connection")
      (d/release conn))
    (assoc this :conn nil)))

(defn new-datomic [config]
  (map->Datomic {:uri (:datomic-uri config)}))

(s/defn save-url! [datomic :- Datomic
                   url-datomic :- wire.datomic/UrlDatomic]
  (let [conn (:conn datomic)
        tx-data [(assoc url-datomic :db/id (str "url-" (:url/id url-datomic)))]]
    @(d/transact conn tx-data)))

(s/defn find-url-by-short-code [datomic :- Datomic
                                 short-code :- s/Str]
  (let [db (d/db (:conn datomic))
        result (d/q '[:find (pull ?e [*])
                      :in $ ?short-code
                      :where [?e :url/short-code ?short-code]]
                    db
                    short-code)]
    (when-let [entity (ffirst result)]
      entity)))

(s/defn find-url-by-id [datomic :- Datomic
                        url-id :- s/Uuid]
  (let [db (d/db (:conn datomic))
        result (d/q '[:find (pull ?e [*])
                      :in $ ?url-id
                      :where [?e :url/id ?url-id]]
                    db
                    url-id)]
    (when-let [entity (ffirst result)]
      entity)))

(s/defn update-url! [datomic :- Datomic
                     url-datomic :- wire.datomic/UrlDatomic]
  (let [conn (:conn datomic)
        db (d/db conn)
        existing (d/q '[:find ?e
                        :in $ ?short-code
                        :where [?e :url/short-code ?short-code]]
                      db
                      (:url/short-code url-datomic))
        entity-id (ffirst existing)]
    (when entity-id
      (let [tx-data [(assoc url-datomic :db/id entity-id)]]
        @(d/transact conn tx-data)))))

(s/defn save-click-event! [datomic :- Datomic
                            click-event :- wire.datomic/ClickEventDatomic]
  (let [conn (:conn datomic)
        tx-data [(assoc click-event :db/id (str "click-" (:click/id click-event)))]]
    @(d/transact conn tx-data)))

(s/defn find-click-events-by-short-code [datomic :- Datomic
                                          short-code :- s/Str]
  (let [db (d/db (:conn datomic))
        results (d/q '[:find (pull ?e [*])
                       :in $ ?short-code
                       :where [?e :click/short-code ?short-code]]
                     db
                     short-code)]
    (map first results)))

(s/defn find-daily-analytics [datomic :- Datomic
                               short-code :- s/Str
                               date :- java.util.Date]
  (let [db (d/db (:conn datomic))
        results (d/q '[:find (pull ?e [*])
                       :in $ ?short-code ?date
                       :where [?e :analytics/short-code ?short-code]
                              [?e :analytics/date ?date]]
                     db
                     short-code
                     date)]
    (map first results)))

(defn save-daily-analytics! [datomic analytics]
  (let [conn (:conn datomic)
        db (d/db conn)
        existing (d/q '[:find ?e
                        :in $ ?sc ?d
                        :where [?e :analytics/short-code ?sc]
                               [?e :analytics/date ?d]]
                      db
                      (:analytics/short-code analytics)
                      (:analytics/date analytics))
        entity-id (ffirst existing)
        tx-data [(if entity-id
                   (assoc analytics :db/id entity-id)
                   (assoc analytics :db/id (d/tempid :db.part/user)))]]
    @(d/transact conn tx-data)))

(s/defn find-all-daily-analytics [datomic :- Datomic
                                   short-code :- s/Str]
  (let [db (d/db (:conn datomic))
        results (d/q '[:find (pull ?e [*])
                       :in $ ?short-code
                       :where [?e :analytics/short-code ?short-code]]
                     db
                     short-code)]
    (->> (map first results)
         (sort-by :analytics/date))))

(defn save-user! [datomic user]
  (let [conn (:conn datomic)
        tx-data [(assoc user :db/id (str "user-" (:user/id user)))]]
    @(d/transact conn tx-data)))

(defn find-user-by-username [datomic username]
  (let [db (d/db (:conn datomic))
        result (d/q '[:find (pull ?e [:user/id :user/username :user/password-hash :user/created-at])
                       :in $ ?username
                       :where [?e :user/username ?username]]
                     db
                     username)]
    (ffirst result)))
