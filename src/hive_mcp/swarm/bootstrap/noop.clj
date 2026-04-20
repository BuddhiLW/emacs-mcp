(ns hive-mcp.swarm.bootstrap.noop
  "NoopBootstrap — explicit Null Object for swarm bootstrap.

   Use when no durable projection is desired: the registry starts empty,
   writes are discarded, and nothing is persisted. Event stream is the
   only source of truth.

   DDD: Null Object pattern keeps consumers branch-free.
   FP: all methods are pure (return `this`), no side effects."
  (:require [hive-mcp.swarm.bootstrap.protocol :as proto]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord NoopBootstrap []
  proto/ISwarmBootstrap
  (-load-slaves [_this]
    (log/info "NoopBootstrap: no slaves to load (event-stream only)")
    [])
  (-snapshot-slave! [this _slave-id _slave-data] this)
  (-forget-slave! [this _slave-id] this)
  (-close! [_this] nil))

(defn make-noop-bootstrap
  "Construct a NoopBootstrap. No options."
  []
  (->NoopBootstrap))
