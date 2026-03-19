(ns url-shortener.observability.metrics
  (:import [io.prometheus.metrics.core.metrics Counter Histogram]
           [io.prometheus.metrics.model.registry PrometheusRegistry]
           [io.prometheus.metrics.instrumentation.jvm JvmMetrics]
           [io.prometheus.metrics.expositionformats PrometheusTextFormatWriter]
           [java.io ByteArrayOutputStream]))

(defonce registry PrometheusRegistry/defaultRegistry)

(defonce request-counter (atom nil))
(defonce request-duration (atom nil))
(defonce urls-created-counter (atom nil))
(defonce redirects-counter (atom nil))
(defonce cache-hit-counter (atom nil))
(defonce cache-miss-counter (atom nil))
(defonce initialized? (atom false))

(defn- register-counter [name help & {:keys [labels]}]
  (let [builder (-> (Counter/builder) (.name name) (.help help))]
    (when labels (.labelNames builder (into-array String labels)))
    (.register builder registry)))

(defn- register-histogram [name help & {:keys [labels]}]
  (let [builder (-> (Histogram/builder) (.name name) (.help help))]
    (when labels (.labelNames builder (into-array String labels)))
    (.register builder registry)))

(defn init-metrics! []
  (when (compare-and-set! initialized? false true)
    (try
      (reset! request-counter
              (register-counter "http_requests_total" "Total HTTP requests"
                                :labels ["method" "path" "status"]))
      (reset! request-duration
              (register-histogram "http_request_duration_seconds" "HTTP request duration in seconds"
                                  :labels ["method" "path" "status"]))
      (reset! urls-created-counter
              (register-counter "urlshortener_urls_created_total" "Total URLs created"))
      (reset! redirects-counter
              (register-counter "urlshortener_redirects_total" "Total URL redirects"))
      (reset! cache-hit-counter
              (register-counter "urlshortener_cache_hits_total" "Total cache hits"))
      (reset! cache-miss-counter
              (register-counter "urlshortener_cache_misses_total" "Total cache misses"))
      (-> (JvmMetrics/builder) (.register registry))
      (catch Exception _))))

(defn inc-request-count [method path status]
  (when-let [c @request-counter]
    (-> c (.labelValues (into-array String [method path status])) .inc)))

(defn observe-request-duration [method path status duration-s]
  (when-let [h @request-duration]
    (-> h (.labelValues (into-array String [method path status])) (.observe duration-s))))

(defn inc-urls-created []
  (when-let [c @urls-created-counter] (.inc c)))

(defn inc-redirects []
  (when-let [c @redirects-counter] (.inc c)))

(defn inc-cache-hit []
  (when-let [c @cache-hit-counter] (.inc c)))

(defn inc-cache-miss []
  (when-let [c @cache-miss-counter] (.inc c)))

(defn export-metrics []
  (let [baos (ByteArrayOutputStream.)
        writer (PrometheusTextFormatWriter. true)]
    (.write writer baos (.scrape registry))
    (.toString baos "UTF-8")))
