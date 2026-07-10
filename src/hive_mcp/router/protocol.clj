(ns hive-mcp.router.protocol
  "L0 contract — router bounded context.

   Two narrow protocols (ISP):

   - `IRouter`    — type → `ProviderSpec` resolution + hot-flip invalidation.
   - `IEscalator` — size-based override (default-tier doc that exceeds the
                    provider's max-tokens auto-routes to a heavy-tier spec).

   Split because the resolution policy and the escalation policy evolve
   independently. A test impl that simulates a single static route
   should not be forced to implement size-based escalation, and vice
   versa.

   Reload-safety: `defonce`-guarded."
  (:require [clojure.string]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private -irouter-defined? (atom false))

(when (compare-and-set! -irouter-defined? false true)
  (defprotocol IRouter
    "Type-driven provider resolution."

    (resolve-for-type [this memory-type]
      "Return the `ProviderSpec` for `memory-type` (a keyword like
       `:type/note` or string `\"note\"`). Falls back to the configured
       default tier when the type has no explicit route. Returns a
       `Result` (Ok spec | Err {:err/tag :router/no-default}).")

    (invalidate! [this]
      "Drop any cached resolution state. Called by hot-flip handlers
       (`apply-route-flip!`, `apply-collection-flip!`) so subsequent
       resolutions reflect the new config. Idempotent.")))

(defonce ^:private -iescalator-defined? (atom false))

(when (compare-and-set! -iescalator-defined? false true)
  (defprotocol IEscalator
    "Size-based escalation."

    (escalate-if-large [this spec doc-size-tokens]
      "If `doc-size-tokens` exceeds `spec`'s `:provider/max-tokens`,
       return the `Result` of resolving the heavy-tier spec; otherwise
       return `(ok spec)` unchanged. Heavy-tier types (plan, book,
       manual, digest, document) are pre-resolved to their venice
       provider, so escalation is a single lookup.")))
