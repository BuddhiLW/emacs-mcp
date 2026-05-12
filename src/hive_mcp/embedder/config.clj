(ns hive-mcp.embedder.config
  "Typed config for the embedder bounded context, resolved via hive-di
   `defconfig`. Reads `[:embedder]` block from
   `~/.config/hive-mcp/config.edn`, with merge.clj defaults filling
   missing keys.

   The block is opaque from defconfig's perspective (a single
   `:type :map` field) — schema validation lives in
   `hive-mcp.embedder.spec` for individual provider entries and in
   `hive-mcp.router.config` for routes. Splitting validation per
   bounded context keeps each defconfig under 80 LOC.

   Why a single :embedder-block field rather than per-key
   defconfig fields: the providers map has dynamic keys
   (`:ollama-qwen3-local`, `:venice-qwen3`, etc.) — defconfig fields
   are static. Pulling the whole block as opaque data and validating
   downstream is the simplest fit."
  (:require [hive-di.core :as di]
            [hive-di.source :as src]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const config-edn-path
  "Canonical hive-mcp config file. Mirrors `hive-mcp.config.io` so
   defconfig stays decoupled from the io ns (which transitively
   pulls in NATS/k8s/etc that this layer must not depend on)."
  (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))

(def ^:private default-block
  "Embedder defaults. Mirrored from `hive-mcp.config.merge` so
   resolution is total when merge.clj's atom is unavailable (during
   pre-boot defconfig sanity checks). Ship 2 will swap merge.clj's
   block to point default at :ollama-qwen3-local; this default-block
   stays neutral for defconfig totality tests."
  {})

(di/defconfig EmbedderConfig
  :embedder-block (src/file config-edn-path
                            [:embedder]
                            :default default-block
                            :type :map
                            :doc "Whole :embedder block: providers + routes + default + escalation."))

(defn resolve!
  "Resolve EmbedderConfig with optional overrides. Returns the
   resolved map directly; throws on resolution failure (defaults
   guarantee resolution under normal conditions)."
  ([] (resolve! {}))
  ([overrides]
   (let [result (resolve-EmbedderConfig overrides)]
     (if (r/ok? result)
       (:ok result)
       (throw (ex-info "EmbedderConfig resolution failed"
                       {:result result :overrides overrides}))))))

(defn block
  "Resolved `:embedder` block as a map. Convenience for L2 facades."
  []
  (:embedder-block (resolve!)))
