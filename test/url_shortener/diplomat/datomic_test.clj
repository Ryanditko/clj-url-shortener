(ns url-shortener.diplomat.datomic-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [url-shortener.diplomat.datomic :as diplomat.datomic]
            [url-shortener.diplomat.datomic.schema :as schema]))

(def test-uri "datomic:mem://url-shortener-test")

(defn setup-test-db []
  (d/delete-database test-uri)
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (schema/migrate! conn)
    conn))

(use-fixtures :each
  (fn [f]
    (setup-test-db)
    (f)
    (d/delete-database test-uri)))

(def sample-url-datomic
  {:url/id #uuid "123e4567-e89b-12d3-a456-426614174000"
   :url/original-url "https://example.com/test"
   :url/short-code "abc123"
   :url/created-at #inst "2024-01-15T10:30:00.000-00:00"
   :url/clicks 0
   :url/active? true})

(deftest save-url!-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})]
    
    (testing "saves URL to database"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (let [result (diplomat.datomic/find-url-by-short-code datomic "abc123")]
        (is (some? result))
        (is (= "https://example.com/test" (:url/original-url result)))
        (is (= "abc123" (:url/short-code result)))))
    
    (testing "enforces unique short-code constraint"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (is (thrown? Exception
                   (diplomat.datomic/save-url! 
                    datomic 
                    (assoc sample-url-datomic 
                           :url/id #uuid "987e6543-e21b-45d3-a987-123456789abc")))))))

(deftest find-url-by-short-code-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})]
    
    (testing "finds existing URL"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (let [result (diplomat.datomic/find-url-by-short-code datomic "abc123")]
        (is (some? result))
        (is (= "abc123" (:url/short-code result)))))
    
    (testing "returns nil for non-existent code"
      (let [result (diplomat.datomic/find-url-by-short-code datomic "nonexistent")]
        (is (nil? result))))))

(deftest find-url-by-id-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})
        url-id #uuid "123e4567-e89b-12d3-a456-426614174000"]
    
    (testing "finds URL by UUID"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (let [result (diplomat.datomic/find-url-by-id datomic url-id)]
        (is (some? result))
        (is (= url-id (:url/id result)))))
    
    (testing "returns nil for non-existent ID"
      (let [result (diplomat.datomic/find-url-by-id 
                    datomic 
                    #uuid "987e6543-e21b-45d3-a987-123456789abc")]
        (is (nil? result))))))

(deftest update-url!-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})]
    
    (testing "updates existing URL"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (let [updated (assoc sample-url-datomic :url/clicks 42)]
        (diplomat.datomic/update-url! datomic updated)
        (let [result (diplomat.datomic/find-url-by-short-code datomic "abc123")]
          (is (= 42 (:url/clicks result))))))
    
    (testing "preserves other fields during update"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (diplomat.datomic/update-url! datomic (assoc sample-url-datomic :url/clicks 10))
      (let [result (diplomat.datomic/find-url-by-short-code datomic "abc123")]
        (is (= "https://example.com/test" (:url/original-url result)))
        (is (= 10 (:url/clicks result)))))))

(deftest save-click-event!-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})
        click-event {:click/id #uuid "987e6543-e21b-45d3-a987-123456789abc"
                     :click/short-code "abc123"
                     :click/timestamp #inst "2024-01-15T15:45:00.000-00:00"
                     :click/user-agent "Mozilla/5.0"
                     :click/ip-address "192.168.1.1"}]
    
    (testing "saves click event"
      (diplomat.datomic/save-click-event! datomic click-event)
      (let [events (diplomat.datomic/find-click-events-by-short-code datomic "abc123")]
        (is (= 1 (count events)))
        (is (= "192.168.1.1" (:click/ip-address (first events))))))))

(deftest find-click-events-by-short-code-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})]
    
    (testing "finds all click events for short code"
      (diplomat.datomic/save-click-event! 
       datomic
       {:click/id #uuid "987e6543-e21b-45d3-a987-123456789abc"
        :click/short-code "abc123"
        :click/timestamp #inst "2024-01-15T15:45:00.000-00:00"})
      
      (diplomat.datomic/save-click-event! 
       datomic
       {:click/id #uuid "456e7890-a12b-34c5-d678-901234567890"
        :click/short-code "abc123"
        :click/timestamp #inst "2024-01-15T15:46:00.000-00:00"})
      
      (let [events (diplomat.datomic/find-click-events-by-short-code datomic "abc123")]
        (is (= 2 (count events)))))
    
    (testing "returns empty for non-existent code"
      (let [events (diplomat.datomic/find-click-events-by-short-code datomic "nonexistent")]
        (is (empty? events))))))

(deftest datomic-time-travel-test
  (let [conn (setup-test-db)
        datomic (diplomat.datomic/map->Datomic {:conn conn})]
    
    (testing "can query historical data"
      (diplomat.datomic/save-url! datomic sample-url-datomic)
      (let [t1 (d/basis-t (d/db conn))]
        (diplomat.datomic/update-url! datomic (assoc sample-url-datomic :url/clicks 10))
        (let [t2 (d/basis-t (d/db conn))
              db-past (d/as-of (d/db conn) t1)
              db-now (d/as-of (d/db conn) t2)
              past-result (d/q '[:find (pull ?e [*])
                                 :in $ ?short-code
                                 :where [?e :url/short-code ?short-code]]
                               db-past
                               "abc123")
              now-result (d/q '[:find (pull ?e [*])
                                :in $ ?short-code
                                :where [?e :url/short-code ?short-code]]
                              db-now
                              "abc123")]
          (is (= 0 (:url/clicks (ffirst past-result))))
          (is (= 10 (:url/clicks (ffirst now-result)))))))))
