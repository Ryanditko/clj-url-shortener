(ns url-shortener.logic.rate-limiter)

(defn create-limiter
  "Creates a rate limiter with configurable limits per route group.
   limits-config: {:api {:max-tokens 30 :refill-per-second 0.5}
                   :redirect {:max-tokens 100 :refill-per-second 1.67}
                   :login {:max-tokens 5 :refill-per-second 0.083}}"
  [limits-config]
  {:buckets (atom {})
   :config limits-config})

(defn- refill-tokens [{:keys [tokens last-refill max-tokens refill-per-second]}]
  (let [now (System/currentTimeMillis)
        elapsed-s (/ (- now last-refill) 1000.0)
        new-tokens (min max-tokens (+ tokens (* elapsed-s refill-per-second)))]
    {:tokens new-tokens :last-refill now}))

(defn check-rate!
  "Checks if request is allowed under rate limit. Returns {:allowed? bool :retry-after seconds}."
  [limiter ip limits-key]
  (let [{:keys [buckets config]} limiter
        {:keys [max-tokens refill-per-second]} (get config limits-key {:max-tokens 100 :refill-per-second 1.0})
        bucket-key (str ip ":" (name limits-key))
        result (atom nil)]
    (swap! buckets
           (fn [state]
             (let [bucket (get state bucket-key
                               {:tokens max-tokens
                                :last-refill (System/currentTimeMillis)
                                :max-tokens max-tokens
                                :refill-per-second refill-per-second})
                   refilled (refill-tokens (assoc bucket :max-tokens max-tokens :refill-per-second refill-per-second))]
               (if (>= (:tokens refilled) 1.0)
                 (do
                   (reset! result {:allowed? true})
                   (assoc state bucket-key (assoc refilled :tokens (dec (:tokens refilled)))))
                 (do
                   (let [wait-s (Math/ceil (/ (- 1.0 (:tokens refilled)) refill-per-second))]
                     (reset! result {:allowed? false :retry-after (int wait-s)}))
                   (assoc state bucket-key refilled))))))
    @result))
