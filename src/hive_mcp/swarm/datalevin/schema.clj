(ns hive-mcp.swarm.datalevin.schema
  "Datalevin schema definitions for swarm hivemind coordination.

   Adapts the DataScript schema for Datalevin's LMDB backend.
   Key differences:
   - Constrained attributes need explicit :db/valueType
   - Unconstrained attributes use Datalevin's open-schema auto-typing
   - :db/doc is not a schema attribute in Datalevin (documentation only)

   Reuses status enumerations and validation from datascript.schema."
  (:require [hive-mcp.swarm.datascript.schema :as ds-schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Re-export status enumerations (shared across backends)
;;; =============================================================================

(def slave-statuses ds-schema/slave-statuses)
(def task-statuses ds-schema/task-statuses)
(def coordinator-statuses ds-schema/coordinator-statuses)
(def plan-statuses ds-schema/plan-statuses)
(def item-statuses ds-schema/item-statuses)
(def wave-statuses ds-schema/wave-statuses)
(def critical-op-types ds-schema/critical-op-types)
(def daemon-statuses ds-schema/daemon-statuses)
(def daemon-health-levels ds-schema/daemon-health-levels)
(def olympus-layout-modes ds-schema/olympus-layout-modes)
(def agent-types ds-schema/agent-types)
(def spawn-modes ds-schema/spawn-modes)
(def ling-model-default ds-schema/ling-model-default)
(def claude-model? ds-schema/claude-model?)
(def task-types ds-schema/task-types)

;;; =============================================================================
;;; Datalevin Schema (constrained attributes only)
;;; =============================================================================
;;
;; Datalevin open-schema auto-types attributes not listed here.
;; We only declare attributes that need:
;;   - :db/unique      (identity lookup)
;;   - :db/valueType   :db.type/ref (entity references)
;;   - :db/cardinality :db.cardinality/many (collections)
;;   - :db/index       true (query performance)

(def schema
  "Datalevin schema for swarm state.

   Only constrained attributes are declared. All other attributes
   are auto-typed by Datalevin's open-schema on first transact."

  {;;; Slave Entity
   :slave/id              {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :slave/parent          {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :slave/current-task    {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :slave/presets         {:db/valueType   :db.type/string
                           :db/cardinality :db.cardinality/many}
   :slave/critical-ops    {:db/valueType   :db.type/keyword
                           :db/cardinality :db.cardinality/many}
   :slave/agent-type      {:db/valueType :db.type/keyword
                           :db/index     true}
   :slave/daemon          {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :ling/spawn-mode       {:db/valueType :db.type/keyword
                           :db/index     true}
   :ling/model            {:db/valueType :db.type/string
                           :db/index     true}

   ;; Universal lifecycle metadata (added 2026-04-27 — registry-ghost fix).
   ;; Indexed: :slave/last-active-at + :slave/alive? for stale-sweep query speed.
   :slave/spawned-at        {:db/valueType :db.type/long}
   :slave/last-active-at    {:db/valueType :db.type/long
                             :db/index     true}
   :slave/status-changed-at {:db/valueType :db.type/long}
   :slave/process-pid       {:db/valueType :db.type/long}
   :slave/alive?            {:db/valueType :db.type/boolean
                             :db/index     true}

   ;;; Task Entity
   :task/id               {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :task/slave            {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :task/files            {:db/valueType   :db.type/string
                           :db/cardinality :db.cardinality/many}

   ;;; Claim Entity
   :claim/file            {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :claim/slave           {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :claim/task            {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}
   :claim/changes         {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/many}
   :claim/kg-edges-created {:db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/many}

   ;;; Claim Change Entity
   :claim-change/id       {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Claim History Entity
   :claim-history/id      {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :claim-history/file    {:db/valueType :db.type/string
                           :db/index     true}

   ;;; Wrap Queue Entity
   :wrap-queue/id         {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :wrap-queue/created-ids {:db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/many}

   ;;; Change Plan Entity
   :change-plan/id        {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Change Item Entity
   :change-item/id        {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :change-item/plan      {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}

   ;;; Wave Entity
   :wave/id               {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :wave/plan             {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}

   ;;; Coordinator Entity
   :coordinator/id        {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Completed Task Entity
   :completed-task/id     {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Wait-Queue Entity
   :wait-queue/id         {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Health Event Entity
   :health-event/id       {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}

   ;;; Olympus Entity
   :olympus/id            {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :olympus/focused-ling  {:db/valueType   :db.type/ref
                           :db/cardinality :db.cardinality/one}

   ;;; Emacs Daemon Entity
   :emacs-daemon/id       {:db/valueType :db.type/string
                           :db/unique    :db.unique/identity}
   :emacs-daemon/lings    {:db/valueType   :db.type/string
                           :db/cardinality :db.cardinality/many}})
