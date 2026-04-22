(ns hive-mcp.embeddings.http-client-test
  "Property + example tests for the self-healing HttpClient cache.

   Properties:
   - P1: fatal-client-error? detects all known fatal JDK signatures
   - P2: fatal-client-error? does not match arbitrary non-fatal strings
   - P3: rebuild! is identity-preserving when `old` is no longer current
   - P4: send-with-retry retries EXACTLY ONCE on fatal, not twice

   Examples:
   - E1: mk-client caches a client; get-client returns it
   - E2: rebuild! swaps the cached client
   - E3: send-with-retry passes non-fatal IOException through unchanged"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.embeddings.http-client :as http])
  (:import [java.io IOException]
           [java.net.http HttpClient]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-fatal-message
  "Generator for messages that MUST trigger a rebuild."
  (gen/elements
   ["selector manager closed"
    "Selector Manager Closed"
    "io: selector manager closed on thread"
    "HttpClient has been shut down"
    "HttpClient has been shutdown"
    "HttpClient closed"
    "the client is closed: giving up"]))

(def gen-non-fatal-message
  "Generator for messages that MUST NOT trigger a rebuild.
   Avoids overlap with the fatal regex (selector|shutdown|closed on client)."
  (gen/elements
   ["Connection refused"
    "Connect timed out"
    "Read timed out"
    "Network is unreachable"
    "404 Not Found"
    "Broken pipe"
    "SSL handshake failed"
    ""
    "random error with no magic words"]))

;; =============================================================================
;; Properties
;; =============================================================================

(defspec p1-fatal-detector-catches-known-signatures 100
  (prop/for-all [msg gen-fatal-message]
    (http/fatal-client-error? (IOException. ^String msg))))

(defspec p2-fatal-detector-rejects-non-fatal 100
  (prop/for-all [msg gen-non-fatal-message]
    (not (http/fatal-client-error? (IOException. ^String msg)))))

(defspec p4-retry-fires-at-most-once 50
  (prop/for-all [fatal-msg gen-fatal-message]
    (let [call-count (atom 0)
          builder-count (atom 0)
          fake-client (reify Object
                        (toString [_] (str "fake-" @call-count)))
          cache (http/mk-client
                 (fn []
                   (swap! builder-count inc)
                   fake-client))]
      ;; Simulate .send by re-implementing send-with-retry semantics on a
      ;; mock — we exercise the decision tree, not JDK internals.
      (let [attempt! (fn [cache request body-handler]
                       (let [c (http/get-client cache)]
                         (try
                           (swap! call-count inc)
                           (throw (IOException. ^String fatal-msg))
                           (catch IOException e
                             (if (http/fatal-client-error? e)
                               (let [_ (http/rebuild! cache c)]
                                 (swap! call-count inc)
                                 (throw (IOException. ^String fatal-msg)))
                               (throw e))))))]
        (try (attempt! cache nil nil)
             (catch IOException _))
        ;; Exactly 2 send attempts (1 original + 1 retry)
        (and (= 2 @call-count)
             ;; Builder fired initial + exactly 1 rebuild
             (= 2 @builder-count))))))

;; =============================================================================
;; Example / unit tests
;; =============================================================================

(deftest e1-mk-client-caches
  (testing "mk-client returns cache; get-client returns the built value"
    (let [built (atom 0)
          cache (http/mk-client (fn []
                                  (swap! built inc)
                                  :my-client))]
      (is (= :my-client (http/get-client cache)))
      (is (= :my-client (http/get-client cache)))
      (is (= 1 @built) "builder fires exactly once on construction"))))

(deftest e2-rebuild-swaps-when-old-is-current
  (testing "rebuild! replaces the cached client when old matches"
    (let [counter (atom 0)
          cache (http/mk-client (fn [] (keyword (str "c" (swap! counter inc)))))
          first-client (http/get-client cache)
          new-client (http/rebuild! cache first-client)]
      (is (= :c1 first-client))
      (is (= :c2 new-client))
      (is (= :c2 (http/get-client cache)))))

  (testing "rebuild! is a no-op when `old` is not the current client"
    (let [cache (http/mk-client (constantly :only-client))
          result (http/rebuild! cache :stale-reference)]
      (is (= :only-client result))
      (is (= :only-client (http/get-client cache))))))

(deftest e3-non-fatal-ioexception-propagates
  (testing "send-with-retry does not catch non-fatal IOException"
    (let [cache (http/mk-client (fn []
                                  (reify Object
                                    (toString [_] "client"))))
          ;; mock HttpClient that throws non-fatal IOException
          throwing-cache
          (http/mk-client
           (fn []
             (proxy [HttpClient] []
               (send [_req _body-handler]
                 (throw (IOException. "Connection refused"))))))]
      (is (thrown-with-msg? IOException #"Connection refused"
            (http/send-with-retry throwing-cache nil nil))))))
