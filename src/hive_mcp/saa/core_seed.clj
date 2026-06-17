(ns hive-mcp.saa.core-seed
  "Seed the SAA registry as the synthetic `:saa/core` owner: the DefaultPhaseProvider,
   DefaultObservationScorer, NoopPlanSynthesizer, and the neutral DEFAULT tool-intent
   entries that back every provider-scoped tool resolution.

   Runs at namespace load via a `defonce` guard so the seed is idempotent and the
   registry is populated before any addon `(hooks [this])` walk arrives.

   External addons can never deregister `:saa/core` entries because
   `deregister-by-owner!` is invoked only with the addon's own id."
  (:require [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.types :as types]
            [hive-mcp.saa.adapters :as adapters]
            [hive-mcp.saa.scorer :as scorer]
            [hive-mcp.saa.planner :as planner]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private core-owner :saa/core)

(def ^:private default-tool-intents
  "Neutral capability → neutral tool tokens. No vendor strings."
  {:read   ["read" "view"]
   :search ["grep" "glob" "find"]
   :web    ["web-fetch" "web-search"]
   :write  ["write" "edit"]
   :exec   ["bash" "shell"]})

(defn- seed-phase-provider!
  "Seed the DefaultPhaseProvider under :saa/default."
  []
  (registry/register-by-key!
   core-owner :saa/phase-provider
   [(types/saa-registry-entry :saa/phase-provider
                              {:provider (adapters/->default-phase-provider)
                               :owner core-owner})])
  1)

(defn- seed-scorer!
  "Seed the DefaultObservationScorer under :saa/default."
  []
  (registry/register-by-key!
   core-owner :saa/scorer
   [(types/saa-registry-entry :saa/scorer
                              {:scorer (scorer/->default-scorer)
                               :owner core-owner})])
  1)

(defn- seed-planner!
  "Seed the NoopPlanSynthesizer under :saa/default."
  []
  (registry/register-by-key!
   core-owner :saa/planner
   [(types/saa-registry-entry :saa/planner
                              {:planner (planner/->noop-planner)
                               :owner core-owner})])
  1)

(defn- seed-tool-intents!
  "Seed the neutral DEFAULT tool-intent entries for #{:read :search :web :write :exec}."
  []
  (doseq [[intent tools] default-tool-intents]
    (registry/register-by-key!
     core-owner :saa/tool-intent
     [(types/saa-registry-entry :saa/tool-intent
                                {:intent intent :tools tools :owner core-owner})]))
  (count default-tool-intents))

(defonce ^{:doc "Seed runs once on namespace load. Idempotent — re-loading the
                 namespace is a no-op because defonce guards the side effect.
                 Call `install!` from a REPL to force re-seed."}
  installed
  (let [providers (seed-phase-provider!)
        scorers (seed-scorer!)
        planners (seed-planner!)
        tool-intents (seed-tool-intents!)]
    (log/info "[saa.core-seed] seeded :saa/core owner"
              {:providers providers :scorers scorers
               :planners planners :tool-intents tool-intents})
    {:providers providers :scorers scorers
     :planners planners :tool-intents tool-intents}))

(defn install!
  "Force re-seed (test/REPL). Production code relies on the defonce guard above."
  []
  (registry/deregister-by-owner! core-owner)
  (let [result {:providers (seed-phase-provider!)
                :scorers (seed-scorer!)
                :planners (seed-planner!)
                :tool-intents (seed-tool-intents!)}]
    (log/info "[saa.core-seed] re-seeded :saa/core owner" result)
    result))
