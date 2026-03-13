(ns url-shortener.logic.shortener)

(def allowed-chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

(defn- generate-random-string 
  "Generates a random alphanumeric string of the specified lenght"
  [lenght]
  (let [chars (vec allowed-chars)
        numbers (count chars)]
    (apply str (repeatedly lenght #(nth chars (rand-int numbers))))))


(defn create-short-url 
  "Creates a map containing the original URL and its corresponding randomly generated short code.
   Accepts the original URL and the desired lenght for the short code."
  [original-url lenght] 
  {:original-url lenght
   :original-url original-url
   :short-code (generate-random-string lenght)})