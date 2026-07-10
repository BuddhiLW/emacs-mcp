;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.engine.bounded.protocol
  "Role-sized DIP port for bounded queue / buffer primitives
   (ENGINE-L1.3, defense-in-depth).

   `IBoundedQueue` is the only abstraction callers need. Concrete
   implementations live in sibling namespaces — `bounded.lru` for the
   flat single-queue and per-key variants — so adding a new policy
   (priority, time-windowed, weighted) is a new module rather than a
   modification of an existing one (OCP).

   Method names are prefixed `q-…` to avoid clashes with java.util.Map
   methods (size, get, keys, …) that defrecord auto-implements.

   All operations return *outcome data*: `:added`, `:added-and-evicted`,
   `:rejected`. This lets observers (metrics, KG audit) react without
   coupling to specific implementations.")

(defprotocol IBoundedQueue
  "Capped append-only buffer with explicit eviction outcome.

   Conventions:
   - Implementations enclose their own state — callers never see the
     underlying atom or sequence.
   - `q-offer!` and `q-offer-key!` are non-blocking. Implementations
     without a key dimension treat `q-offer-key!` as `q-offer!` and
     ignore `k`.
   - `q-drain!` MUST be atomic with respect to concurrent `q-offer!`s;
     it returns the prior contents and resets state to empty.
   - `q-size` and `q-stats` are O(1) snapshots."
  (q-offer!      [this item]   "Append `item`. Returns {:outcome <kw> :evicted ?}.")
  (q-offer-key!  [this k item] "Per-key variant. Returns {:outcome <kw> :evicted ? :key k}.")
  (q-drain!      [this]        "Atomically take + reset. Returns the drained payload.")
  (q-snapshot    [this]        "Read-only snapshot of contents.")
  (q-size        [this]        "Current item count.")
  (q-stats       [this]        "Diagnostic map: :size :capacity :added :evicted :rejected."))
