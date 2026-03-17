(ns url-shortener.logic.shortener
  (:require [clojure.string :as string]))

(def ^:private base62-chars
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defn number->base62 [n]
  {:pre [(nat-int? n)]}
  (if (zero? n)
    "0"
    (loop [num n
           result ""]
      (if (zero? num)
        result
        (let [remainder (mod num 62)
              char (nth base62-chars remainder)]
          (recur (quot num 62) (str char result)))))))

(defn base62->number [s]
  {:pre [(string? s) (not (empty? s))]}
  (reduce (fn [acc char]
            (let [value (.indexOf base62-chars (str char))]
              (when (neg? value)
                (throw (ex-info "Invalid Base62 character"
                                {:type :invalid-base62
                                 :char char
                                 :string s})))
              (+ (* acc 62) value)))
          0
          s))

(defn generate-short-code-from-id [numeric-id]
  {:pre [(nat-int? numeric-id)]}
  (number->base62 numeric-id))

(defn generate-short-code-from-timestamp [timestamp length]
  {:pre [(nat-int? timestamp) (pos-int? length) (>= length 3)]}
  (let [ts-base62 (number->base62 timestamp)
        ts-length (count ts-base62)
        random-length (max 1 (- length ts-length))
        random-suffix (apply str (repeatedly random-length
                                              #(nth base62-chars (rand-int 62))))]
    (str ts-base62 random-suffix)))

(defn valid-url? [url-string]
  (boolean
   (and (string? url-string)
        (not (empty? url-string))
        (re-matches #"^https?://.*" url-string)
        (< (count url-string) 2048))))

(defn valid-custom-code? [custom-code]
  (boolean
   (let [reserved-codes #{"api" "admin" "stats" "health" "metrics"}]
     (and (string? custom-code)
          (re-matches #"^[a-zA-Z0-9]{3,12}$" custom-code)
          (not (reserved-codes (string/lower-case custom-code)))))))

(defn url-expired? [url current-time]
  (when-let [expires-at (:expires-at url)]
    (.before expires-at current-time)))

(defn calculate-expiration [start-date duration-days]
  {:pre [(inst? start-date) (pos-int? duration-days)]}
  (let [instant (.toInstant start-date)
        local-date (.atZone instant (java.time.ZoneId/of "UTC"))
        new-date (.plusDays local-date duration-days)
        new-instant (.toInstant new-date)]
    (java.util.Date/from new-instant)))

(defn increment-clicks [url]
  (update url :clicks (fnil inc 0)))

(defn calculate-stats [url click-events]
  {:short-code (:short-code url)
   :total-clicks (count click-events)
   :created-at (:created-at url)
   :last-accessed (when (seq click-events)
                    (let [timestamps (map :timestamp click-events)]
                      (reduce (fn [max-date date]
                                (if (.after date max-date)
                                  date
                                  max-date))
                              (first timestamps)
                              (rest timestamps))))
   :unique-visitors (->> click-events
                         (map :ip-address)
                         (filter some?)
                         distinct
                         count)})

(defn codes-collide? [code1 code2]
  (= code1 code2))

(defn generate-alternative-code [existing-code]
  {:pre [(string? existing-code)]}
  (str existing-code (nth base62-chars (rand-int 62))))
