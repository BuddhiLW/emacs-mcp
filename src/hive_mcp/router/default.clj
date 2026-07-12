(ns hive-mcp.router.default
  "L2 — default `IRouter` + `IEscalator` impl.

   Thin wrapper over the pure resolvers in
   `hive-mcp.router.{resolve,escalate}`. Reads the `:embedder` block
   lazily on each call so hot-flips (`apply-route-flip!`) propagate
   immediately — never captures the resolved snapshot in a closure."
  (:require [hive-mcp.embedder.config :as econfig]
            [hive-mcp.router.escalate :as esc]
            [hive-mcp.router.protocol :as proto]
            [hive-mcp.router.resolve :as resolve]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- read-block
  "Read the `:embedder` block via the supplied 0-arg fn. Defaults to
   `econfig/block` so production wiring picks up the live config; tests
   can pass a constant fn to pin the block."
  [block-fn]
  (block-fn))

(defrecord DefaultRouter [block-fn invalidated?]
  proto/IRouter
  (resolve-for-type [_ memory-type]
    (resolve/resolve-spec (read-block block-fn) memory-type))
  (invalidate! [_]
    ;; nothing to evict — resolver re-reads the block each call; flag is
    ;; observability only (a flip observer polls :invalidated?)
    (reset! invalidated? true))

  proto/IEscalator
  (escalate-if-large [_ spec doc-size-tokens]
    (esc/maybe-escalate (read-block block-fn) spec doc-size-tokens)))

(defn make-router
  "Construct a `DefaultRouter`. Takes an optional 0-arg `block-fn` for
   testability; in production callers omit it to pick up
   `econfig/block` automatically."
  ([] (make-router econfig/block))
  ([block-fn]
   (->DefaultRouter block-fn (atom false))))
