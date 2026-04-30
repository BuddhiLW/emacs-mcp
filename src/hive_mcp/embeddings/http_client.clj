(ns hive-mcp.embeddings.http-client
  "Self-healing HttpClient cache for embedding providers.

   Problem
   -------
   A `defonce ^:private http-client (delay (HttpClient/newBuilder ...))` caches
   a JDK HttpClient for the lifetime of the JVM. When a JVM OOM (or other fatal
   error) kills Netty's selector manager thread, the cached client is
   permanently broken. All subsequent `.send` calls throw
   `IOException: selector manager closed`. `ns :reload` does NOT rebuild a
   defonce, so the only recovery was a JVM restart or an ad-hoc
   `alter-var-root`.

   Fix
   ---
   Hold the client in an atom (per-provider) with a builder fn. A wrapper
   `send-with-retry` catches IOException whose message matches the fatal
   client signature, atomically rebuilds the client once, and retries
   exactly once. A second fatal failure bubbles up.

   Usage
   -----
     (def ^:private client (mk-client
                             (fn []
                               (-> (HttpClient/newBuilder)
                                   (.connectTimeout (Duration/ofSeconds 30))
                                   (.build)))))

     (send-with-retry client request (HttpResponse$BodyHandlers/ofString))

   Thread-safety: `compare-and-set!` on the atom ensures only one thread wins
   the rebuild race; losing threads pick up the winner's new client."
  (:require [taoensso.timbre :as log])
  (:import [java.io IOException]
           [java.net.http HttpClient]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn fatal-client-error?
  "True when the exception indicates the underlying HttpClient is dead and
   must be rebuilt. Matches the known-fatal JDK HttpClient signatures:

   - 'selector manager closed' - Netty selector thread died (often after OOM)
   - 'HttpClient has been shut down' / 'closed' - explicit shutdown"
  [^Throwable t]
  (when-let [msg (.getMessage t)]
    (boolean
     (re-find #"(?i)selector manager closed|httpclient.*(shut ?down|closed)|client.*closed"
              msg))))

(defn mk-client
  "Build a self-healing client cache.

   `builder-fn` is a 0-arity fn that returns a fresh HttpClient. Returns a
   map with:
     :builder  - the builder fn (for rebuilds)
     :client   - atom holding the current HttpClient"
  [builder-fn]
  {:builder builder-fn
   :client  (atom (builder-fn))})

(defn get-client
  "Return the live HttpClient from a cache."
  ^HttpClient [cache]
  @(:client cache))

(defn rebuild!
  "Atomically replace the cached client with a fresh one IF `old` is still
   the current value. Losing threads see the winner's new client without
   constructing a throwaway.

   Returns the (possibly new) current client."
  ^HttpClient [cache ^HttpClient old]
  (let [client-atom (:client cache)
        builder     (:builder cache)
        current     @client-atom]
    (if (identical? current old)
      (let [fresh (builder)]
        (if (compare-and-set! client-atom old fresh)
          (do (log/warn "Rebuilt HttpClient after fatal selector/shutdown error")
              fresh)
          ;; A concurrent rebuild! won; discard our fresh client and use theirs.
          @client-atom))
      current)))

(defn send-with-retry
  "Send `request` via the cached client. On a fatal client error
   (see `fatal-client-error?`), rebuild the client once and retry. A second
   fatal failure bubbles up.

   Non-fatal IOExceptions (timeouts, refused connections, etc.) are NOT
   retried - they propagate immediately so callers can surface them."
  [cache request body-handler]
  (let [client (get-client cache)]
    (try
      (.send client request body-handler)
      (catch IOException e
        (if (fatal-client-error? e)
          (let [fresh (rebuild! cache client)]
            (log/info "Retrying embedding request on rebuilt HttpClient")
            (.send fresh request body-handler))
          (throw e))))))
