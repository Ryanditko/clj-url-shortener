(ns url-shortener.logic.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers])
  (:import [java.time Instant Duration]))

(defn hash-password [raw-password]
  (hashers/derive raw-password {:alg :bcrypt+sha512}))

(defn check-password [raw-password hashed]
  (hashers/check raw-password hashed))

(defn generate-token [username jwt-secret ttl-minutes]
  (let [now (Instant/now)
        exp (.plus now (Duration/ofMinutes ttl-minutes))
        claims {:sub username
                :iat (.getEpochSecond now)
                :exp (.getEpochSecond exp)}]
    (jwt/sign claims jwt-secret {:alg :hs256})))

(defn validate-token [token jwt-secret]
  (try
    (let [claims (jwt/unsign token jwt-secret {:alg :hs256 :now (java.time.Instant/now)})]
      {:valid? true :claims claims})
    (catch clojure.lang.ExceptionInfo e
      (let [cause (:cause (ex-data e))]
        (if (= cause :exp)
          {:valid? false :error :token-expired}
          {:valid? false :error :invalid-token})))
    (catch Exception _
      {:valid? false :error :invalid-token})))

(defn extract-bearer-token [authorization-header]
  (when (and authorization-header
             (.startsWith authorization-header "Bearer "))
    (subs authorization-header 7)))
