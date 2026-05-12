(ns hive-mcp.resilience.classify
  "L1 (pure) — Throwable → `ErrorClass` ADT.

   The classifier walks the `.getCause` chain and maps each link to
   a candidate variant. The first non-`:err/unknown` candidate wins,
   following the precedence order encoded in `link-class`:

     schema-mismatch  ←  Milvus server code ≥ 1100 (validation/schema)
                          OR `:err/tag :embedder/dim-mismatch`
                          OR `:err/tag :collection/dim-mismatch`
     auth             ←  HTTP 401/403 status OR :err/tag :auth/*
     transient        ←  IOException OR `:cause :io` OR
                          milvus-clj.client/classify-error returns
                          `:connection-failure`/`:retryable`
     validation       ←  `:err/tag :embedder/input-too-large` and family
     unknown          ←  none of the above

   This precedence order matters. A schema-mismatch wrapped in an
   ExecutionException MUST NOT be classified as transient just
   because the wrapper carries the milvus-clj `::transport` tag —
   that's the precise bug we are fixing.

   No I/O. Property-testable. Single fn `classify` is the public
   surface; everything else is private."
  (:require [clojure.string :as str]
            [hive-mcp.resilience.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private ^:const max-cause-depth
  "Belt-and-braces cap on `.getCause` chain traversal — same constant
   used by `hive-milvus.failure`. Real chains rarely exceed 5 links."
  10)

(defn- causal-chain
  "Seq of `t` and every `.getCause` link. Stops on nil, identity
   cycle, or `max-cause-depth`."
  [^Throwable t]
  (loop [t t acc []]
    (if (or (nil? t)
            (>= (count acc) max-cause-depth)
            (some #(identical? t %) acc))
      acc
      (recur (.getCause t) (conj acc t)))))

(def ^:private milvus-validation-code-floor
  "Milvus server codes ≥ this floor signal validation/schema errors
   (dim mismatch, missing field, malformed row). Codes < 1100 are
   transport-level. The 1804 dim-mismatch sits at 1804; other known
   schema codes (1100, 1101, 1805) all fall in the same range."
  1100)

(defn- ->msg
  "Coerce any value (string, throwable, keyword, nil) into a non-nil
   string suitable for `:message string?` schema validation."
  [v]
  (cond
    (nil? v)            ""
    (string? v)         v
    (instance? Throwable v) (or (.getMessage ^Throwable v) (str v))
    :else               (str v)))

(defn- ex-data-class
  "Examine the ex-data of a single throwable link. Returns an
   `ErrorClass` variant or nil if the link's ex-data carries no
   classifiable signal."
  [link]
  (when-let [data (ex-data link)]
    (cond
      ;; Explicit dim-mismatch tags from our own pipeline
      (#{:embedder/dim-mismatch :collection/dim-mismatch} (:err/tag data))
      (proto/error-class :err/schema-mismatch
                         {:message (->msg (or (:err/cause data) (:err/tag data)))
                          :details data})

      ;; Milvus server-validation code (1804 family)
      (and (:milvus-clj.client/transport data)
           (some-> (:code data) int (>= milvus-validation-code-floor)))
      (proto/error-class :err/schema-mismatch
                         {:message (->msg (or (:message data)
                                              (str "Milvus code=" (:code data))))
                          :details (select-keys data [:code :message :path])})

      ;; HTTP auth surfaces
      (and (:milvus-clj.client/transport data)
           (#{401 403} (:status data)))
      (proto/error-class :err/auth
                         {:message (->msg (or (:message data)
                                              (str "HTTP " (:status data))))})

      ;; Milvus-clj declares :cause :io for genuine transport drops
      (= :io (:cause data))
      (proto/error-class :err/transient
                         {:message (->msg (some-> link .getMessage))})

      ;; Caller-side input validation tags
      (#{:embedder/input-too-large :router/no-default} (:err/tag data))
      (proto/error-class :err/validation
                         {:message (->msg (or (some-> link .getMessage)
                                              (:err/tag data)))}))))

(defn- io-class
  "True when `link` is itself an IOException — covers transports that
   throw raw IOException without wrapping in ex-info."
  [link]
  (when (instance? java.io.IOException link)
    (proto/error-class :err/transient
                       {:message (->msg (or (some-> link .getMessage)
                                            "IOException"))})))

(def ^:private transient-message-markers
  "Fallback markers for ExecutionException wrappers whose ex-data is
   lost on the wrap. Matches `hive-mcp.vectordb.resilience` legacy
   markers verbatim so behavior is unchanged for genuine transients."
  ["selector manager closed" "IO failure" "Connection reset"
   "Broken pipe" "UNAVAILABLE" "DEADLINE_EXCEEDED" "Keepalive failed"
   "connection is likely gone" "not connected"])

(defn- message-class
  "Last-resort marker check on the throwable's message string."
  [link]
  (when-let [msg (some-> link .getMessage)]
    (when (some #(str/includes? msg %) transient-message-markers)
      (proto/error-class :err/transient {:message msg}))))

(defn- link-class
  "Map a single chain link to an `ErrorClass` or nil. Precedence:
   ex-data tags > IOException > message markers."
  [link]
  (or (ex-data-class link)
      (io-class link)
      (message-class link)))

(defn classify
  "Total fn — Throwable → `ErrorClass` ADT. Never throws."
  [^Throwable t]
  (or (some link-class (causal-chain t))
      (proto/error-class :err/unknown
                         {:message (->msg (some-> t .getMessage))})))

(deftype DefaultClassifier []
  proto/IErrorClassifier
  (classify [_ ex] (classify ex)))

(defn default-classifier
  "Construct the default `IErrorClassifier`. No state — safe to share."
  []
  (->DefaultClassifier))
