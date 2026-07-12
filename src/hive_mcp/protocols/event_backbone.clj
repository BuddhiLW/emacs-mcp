(ns hive-mcp.protocols.event-backbone
  "Protocol definitions for event backbone (pub/sub) backends.

   Abstracts NATS/Redis/Kafka behind an IEventBackbone protocol so that
   domain code never depends on a specific messaging system directly.

   Pattern: follows protocols/editor.clj (active atom + NoopBackbone fallback).
   Headless mode is a first-class citizen — NoopBackbone degrades gracefully."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IEventBackbone Protocol
;;; ============================================================================

(defprotocol IEventBackbone
  "Backend protocol for event pub/sub backbone.

   Implementations:
   - NatsBackbone   (nats/backbone.clj)
   - NoopBackbone   (this file, headless fallback)
   - [future: RedisBackbone, KafkaBackbone, ...]"

  (backbone-id [this]
    "Return keyword identifying this backbone backend.
     Examples: :nats, :noop, :redis")

  (connected? [this]
    "Return true if the backbone is reachable and operational.")

  (publish! [this subject payload]
    "Publish a message to a subject/topic.
     payload is a Clojure map — serialization is the backend's responsibility.
     No-op if disconnected. Returns nil.")

  (subscribe! [this subject handler-fn]
    "Subscribe to a subject/topic with a handler function.
     handler-fn receives a deserialized Clojure map.
     Returns an opaque subscription handle, or nil if disconnected.")

  (unsubscribe! [this subject]
    "Unsubscribe from a subject/topic. Safe to call when not subscribed."))

;;; ============================================================================
;;; Active Backbone Management
;;; ============================================================================

(declare ->NoopBackbone)

(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? IEventBackbone %)
                    :on-empty #(->NoopBackbone)}))

(defn set-backbone!
  "Set the active event backbone implementation."
  [backbone]
  (reg/install! slot backbone))

(defn backbone-set?
  "Check if a backbone has been explicitly configured."
  []
  (reg/present? slot))

(defn get-backbone
  "Get the active backbone. Returns NoopBackbone when none is configured
   (headless mode is a first-class citizen)."
  []
  (reg/current slot))

(defn clear-backbone!
  "Clear the active backbone."
  []
  (reg/clear! slot))

(defn add-backbone-watch!
  "Run `(f old new)` when the active backbone changes (e.g. Noop->Nats)."
  [key f]
  (reg/watch-slot! slot key f))

(defn remove-backbone-watch!
  "Remove the backbone watch registered under `key`. Safe when absent."
  [key]
  (reg/unwatch-slot! slot key))

;;; ============================================================================
;;; NoopBackbone (Headless Fallback)
;;; ============================================================================

(defrecord NoopBackbone []
  IEventBackbone
  (backbone-id [_this] :noop)
  (connected? [_this] false)
  (publish! [_this _subject _payload] nil)
  (subscribe! [_this _subject _handler-fn] nil)
  (unsubscribe! [_this _subject] nil))

(defn noop-backbone
  "Create a no-op backbone fallback."
  []
  (->NoopBackbone))
