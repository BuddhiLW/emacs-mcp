(ns hive-mcp.embeddings.routing
  "Per-memory-type embedding route flips.

   The boot-time route flip is **idempotent against user config**: if a user
   has pinned a route via `hive config set embedder.routes.type/<X> :id`,
   that pin survives even when the secret triggering the default flip is
   present. Only routes still equal to the merge.clj `default-id` are
   replaced.

   Design: defn (not macro) — arguments are plain runtime values; no
   compile-time benefit to a macro. Composes hive-help message primitives
   for log output (backticks, join-lines)."
  (:require [clojure.tools.logging :as log]
            [hive-help.core :as hh]
            [hive-mcp.config.core :as global-config]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Message Builders (hive-help primitives)
;; =============================================================================

(defn- flip-message
  "Log line emitted when the macro-controlled route flip activates."
  [{:keys [secret route from to reason]}]
  (hh/join-lines
   (format "%s key detected — %s flipped from %s to %s"
           (hh/backtick secret)
           (hh/backtick route)
           (hh/backtick from)
           (hh/backtick to))
   (when reason (str "  → " reason))))

(defn- pinned-message
  "Log line emitted when the route is held by user config (non-default).
   Tolerates nil current (fresh atom, no defaults loaded yet)."
  [{:keys [secret route current to]}]
  (hh/join-lines
   (format "%s key detected, but %s is %s — leaving as-is"
           (hh/backtick secret)
           (hh/backtick route)
           (if current
             (str "pinned to " (hh/backtick current) " by user config")
             "unset (no merge.clj default)"))
   (str "  (would have flipped to " (hh/backtick to) ")")))

;; =============================================================================
;; Public API
;; =============================================================================

(defn apply-collection-flip!
  "Apply a boot-time collection-level provider flip with user-intent guard.

   Mirror of `apply-route-flip!` at the collection granularity. Use from
   `service/configure-defaults!` when the call would otherwise unconditionally
   override a user pin at `[:embedder :collections <collection>]`.

   Activation:
   - If the user has pinned a provider id under [:embedder :collections coll]
     that differs from `:default`, leave it alone (`:pinned`).
   - Else apply the requested config via the supplied `configure-fn` and log.

   Args (single options map):
   - :collection   — string, e.g. \"hive-mcp-presets\"
   - :default      — keyword the merge.clj defaults assign (often :ollama)
   - :to-id        — keyword the new config represents (e.g. :openrouter)
   - :configure-fn — 0-arg fn that performs the actual configure-collection!
                     call. Wrapped in a try/catch so a misconfigured provider
                     doesn't bubble — the existing pin survives.
   - :reason       — optional human-readable rationale (appended to log)

   Returns: :flipped, :pinned, or :error (action taken, for testing).

   Audit kanban 20260429203437 — closes the gap where presets/plans
   collection routings were unconditional while only :type/plan honored
   user pins."
  [{:keys [collection default to-id configure-fn reason]}]
  (let [pinned (-> (global-config/get-global-config)
                   :embedder :collections (get collection))]
    (if (and pinned (not= default pinned))
      (do (log/info
            (hh/join-lines
             (format "%s pinned to %s by user config — leaving as-is"
                     (hh/backtick collection)
                     (hh/backtick pinned))
             (str "  (would have configured " (hh/backtick to-id) ")")))
          :pinned)
      (try
        (configure-fn)
        (log/info
          (hh/join-lines
           (format "%s configured with %s"
                   (hh/backtick collection)
                   (hh/backtick to-id))
           (when reason (str "  → " reason))))
        :flipped
        (catch Exception e
          (log/warn (format "%s configure failed: %s — leaving unset"
                            (hh/backtick collection) (.getMessage e)))
          :error)))))

(defn apply-route-flip!
  "Apply a boot-time per-type embedding route flip.

   Activates when:
   - the named secret resolves via global-config (config → env → pass)
   - the live route at `[:embedder :routes <route>]` still equals `default`

   Otherwise the user's pin survives. Pure side-effect: mutates the
   in-memory config atom via `global-config/update-in-config!` (JVM-lifetime,
   no disk write — boot-time semantics matching `service/configure-defaults!`).

   Args (single options map):
   - :route   — keyword like :type/plan
   - :default — keyword the merge.clj defaults assign to this route
   - :to      — keyword to flip to when default is in place
   - :secret  — keyword for `global-config/get-secret` lookup
   - :reason  — optional human-readable rationale (appended to flip log line)

   Returns: :flipped, :pinned, or :no-secret (the action taken, for testing)."
  [{:keys [route default to secret reason]}]
  (if-not (global-config/get-secret secret)
    :no-secret
    (let [current (-> (global-config/get-global-config)
                      :embedder :routes route)]
      (if (= default current)
        (do (global-config/update-in-config!
             [:embedder :routes] assoc route to)
            (log/info (flip-message
                       {:secret secret :route route
                        :from default :to to :reason reason}))
            :flipped)
        (do (log/info (pinned-message
                       {:secret secret :route route
                        :current current :to to}))
            :pinned)))))