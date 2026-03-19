(ns url-shortener.logic.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [url-shortener.logic.auth :as auth]
            [buddy.sign.jwt :as jwt]))

(def test-secret "test-secret-for-jwt-min-32-chars-long!")

(deftest hash-and-check-password-test
  (testing "hashed password validates correctly"
    (let [raw "my-secret-key"
          hashed (auth/hash-password raw)]
      (is (true? (auth/check-password raw hashed)))))

  (testing "wrong password fails validation"
    (let [hashed (auth/hash-password "correct-password")]
      (is (false? (auth/check-password "wrong-password" hashed))))))

(deftest generate-and-validate-token-test
  (testing "generates a valid JWT token"
    (let [token (auth/generate-token "testuser" test-secret 60)
          {:keys [valid? claims]} (auth/validate-token token test-secret)]
      (is (true? valid?))
      (is (= "testuser" (:sub claims)))
      (is (number? (:iat claims)))
      (is (number? (:exp claims)))))

  (testing "rejects token with wrong secret"
    (let [token (auth/generate-token "testuser" test-secret 60)
          {:keys [valid? error]} (auth/validate-token token "wrong-secret-needs-to-be-long-enough!")]
      (is (false? valid?))
      (is (= :invalid-token error))))

  (testing "rejects expired token"
    (let [expired-claims {:sub "testuser"
                          :iat 1000000
                          :exp 1000001}
          token (buddy.sign.jwt/sign expired-claims test-secret {:alg :hs256})
          {:keys [valid? error]} (auth/validate-token token test-secret)]
      (is (false? valid?))
      (is (= :token-expired error)))))

(deftest extract-bearer-token-test
  (testing "extracts token from valid header"
    (is (= "abc123" (auth/extract-bearer-token "Bearer abc123"))))

  (testing "returns nil for missing header"
    (is (nil? (auth/extract-bearer-token nil))))

  (testing "returns nil for non-bearer header"
    (is (nil? (auth/extract-bearer-token "Basic abc123"))))

  (testing "returns nil for empty bearer"
    (is (= "" (auth/extract-bearer-token "Bearer ")))))
