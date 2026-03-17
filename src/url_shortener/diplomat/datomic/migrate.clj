(ns url-shortener.diplomat.datomic.migrate
  (:require [datomic.api :as d]
            [url-shortener.diplomat.datomic.schema :as schema]
            [clojure.tools.logging :as log]))

(defn -main [& args]
  (let [uri (or (first args) "datomic:mem://url-shortener")]
    (log/info "Creating database" {:uri uri})
    (d/create-database uri)
    
    (let [conn (d/connect uri)]
      (log/info "Running migrations")
      (schema/migrate! conn)
      (log/info "Migrations completed successfully"))
    
    (System/exit 0)))
