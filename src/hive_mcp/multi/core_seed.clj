(ns hive-mcp.multi.core-seed
  "Project the existing 20 consolidated tools + 36 DSL verbs + 9 param aliases
   into multi.registry as the synthetic `:multi/core` owner.

   Runs at namespace load via a `defonce` guard so the seed is idempotent and
   the registry is populated before any addon `(hooks [this])` walk arrives.

   This decouples multi.handler from consolidated.multi at the type level
   (DIP) — the handler dispatches through the registry, not the literal map.

   External addons can never deregister `:multi/core` entries because
   `deregister-by-owner!` is invoked only with the addon's own id.

   Decision: 20260429230453-7e7627cc"
  (:require [hive-mcp.multi.registry :as registry]
            [hive-mcp.dsl.verbs :as dsl-verbs]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private core-owner :multi/core)

(defn- seed-tools!
  "Seed registry.tools from hive-mcp.tools.consolidated.multi/tool-handlers.

   Lazy-resolved to avoid a hard load-order coupling between multi.registry
   and consolidated.multi (which itself imports many tool nss)."
  []
  (let [handlers-var (try (requiring-resolve 'hive-mcp.tools.consolidated.multi/tool-handlers)
                          (catch Exception _ nil))]
    (if-not handlers-var
      (do (log/warn "[multi.core-seed] consolidated.multi/tool-handlers not resolvable — skipping tool seed")
          0)
      (let [handlers @handlers-var]
        (doseq [[k handler] handlers]
          (registry/register-by-key! core-owner :multi/tool
                                     [{:tool-name (name k) :handler handler}]))
        (count handlers)))))

(defn- seed-verbs!
  "Seed registry.verbs from hive-mcp.dsl.verbs/verb-table."
  []
  (let [table dsl-verbs/verb-table]
    (doseq [[code {:keys [tool command]}] table]
      (registry/register-by-key! core-owner :multi/verb
                                 [{:code code :tool tool :command command}]))
    (count table)))

(defn- seed-aliases!
  "Seed registry.aliases from hive-mcp.dsl.verbs/param-aliases."
  []
  (let [aliases dsl-verbs/param-aliases]
    (doseq [[short full] aliases]
      (registry/register-by-key! core-owner :multi/param-alias
                                 [{:short short :full full}]))
    (count aliases)))

(defonce ^{:doc "Seed runs once on namespace load. Idempotent — re-loading
                 the namespace is a no-op because defonce guards the side
                 effect. Call `install!` from a REPL to force re-seed
                 (after `registry/reset-for-test!`)."}
  installed
  (let [tools (seed-tools!)
        verbs (seed-verbs!)
        aliases (seed-aliases!)]
    (log/info "[multi.core-seed] seeded :multi/core owner"
              {:tools tools :verbs verbs :aliases aliases})
    {:tools tools :verbs verbs :aliases aliases}))

(defn install!
  "Force re-seed (test/REPL). Production code should rely on the defonce
   guard above which fires automatically on namespace load."
  []
  (registry/deregister-by-owner! core-owner)
  (let [result {:tools (seed-tools!)
                :verbs (seed-verbs!)
                :aliases (seed-aliases!)}]
    (log/info "[multi.core-seed] re-seeded :multi/core owner" result)
    result))
