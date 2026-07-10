(ns hive-mcp.tools.swarm.collect
  "Swarm collect handler — JVM-first, Emacs-optional.

   Domain: retrieve a task result, with journal-immediate priority.
   Application: orchestrate poll strategy selection (emacs vs jvm).
   Infrastructure: Emacs eval and event-journal are injected via protocols.

   Bounded contexts:
   - Journal domain   → channel ns (pure atom reads/writes)
   - Emacs infra      → emacs.client (I/O adapter)
   - Strategy domain  → PollStrategy ADT (closed variant set)"
  (:require [hive-mcp.tools.swarm.core    :as core]
            [hive-mcp.tools.swarm.channel :as channel]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.dns.validation      :as v]
            [hive-dsl.adt                 :refer [defadt adt-case]]
            [clojure.data.json            :as json]
            [taoensso.timbre              :as log]
            [hive-dsl.result              :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Domain ADT — closed poll strategy set (OCP: extend by adding variants)
;; =============================================================================

(defadt PollStrategy
  "Closed set of strategies for collecting a task result.
   :strategy/emacs — delegate to Emacs swarm addon (fallback poll).
   :strategy/jvm   — pure JVM bounded-atom backoff poll. No Emacs required."
  :strategy/emacs
  :strategy/jvm)

(defn- select-strategy
  "Determine the poll strategy. Pure selector — reads Emacs availability flag."
  []
  (poll-strategy
   (if (core/swarm-addon-available?) :strategy/emacs :strategy/jvm)))

;; =============================================================================
;; Response builders — one level of abstraction, one concern each (SRP)
;; =============================================================================

(defn- build-journal-response
  "Build MCP response from event-journal entry."
  [task_id journal-event start-time via]
  (let [{:keys [status result error slave-id]} journal-event]
    (core/mcp-success
     {:task_id   task_id
      :status    status
      :result    result
      :error     error
      :slave_id  slave-id
      :via       via
      :elapsed_ms (- (System/currentTimeMillis) start-time)})))

(defn- build-timeout-response
  "Build MCP response for collection timeout. `via` names the polling path."
  [task_id elapsed via]
  {:type "text"
   :text (json/write-str {:task_id    task_id
                          :status     "timeout"
                          :error      (format "Collection timed out after %dms (via: %s)" elapsed via)
                          :elapsed_ms elapsed})})

(defn- build-elisp-timeout-response
  "Build MCP response for an Elisp eval timeout."
  [task_id elapsed]
  {:type    "text"
   :text    (json/write-str {:task_id    task_id
                             :status     "error"
                             :error      "Elisp evaluation timed out"
                             :elapsed_ms elapsed})
   :isError true})

(defn- build-parse-error-response
  "Build MCP response when JSON parsing of Elisp output fails."
  [task_id elapsed raw-result]
  {:type    "text"
   :text    (json/write-str {:task_id    task_id
                             :status     "error"
                             :error      "Failed to parse elisp response"
                             :raw_result raw-result
                             :elapsed_ms elapsed})
   :isError true})

;; =============================================================================
;; Domain predicates — named, single-purpose (CLARITY + SLAP)
;; =============================================================================

(defn- terminal-status?
  "True when the parsed Elisp result represents a final (non-polling) state."
  [parsed]
  (contains? #{"completed" "timeout" "error"} (:status parsed)))

(defn- parse-collect-result
  "Parse raw Elisp JSON string. Returns keyword-keyed map or nil on failure."
  [raw]
  (rescue nil (json/read-str raw :key-fn keyword)))

;; =============================================================================
;; Emacs infra adapter — one concern: execute a single Elisp poll round
;; =============================================================================

(defn- poll-once
  "Execute one Elisp poll against the swarm addon.
   Returns a final response map when done, nil when should keep polling.
   SLAP: operates at infrastructure level — Elisp I/O only."
  [task_id timeout_ms start-time elisp-timeout]
  (let [elapsed (- (System/currentTimeMillis) start-time)
        elisp   (format "(json-encode (hive-mcp-swarm-api-collect \"%s\" %s))"
                        (v/escape-elisp-string task_id)
                        (or timeout_ms "nil"))
        {:keys [success result error timed-out]} (ec/eval-elisp-with-timeout elisp elisp-timeout)]
    (cond
      timed-out
      (build-elisp-timeout-response task_id elapsed)

      (not success)
      (core/mcp-error (str "Error: " error))

      :else
      (let [parsed (parse-collect-result result)]
        (cond
          (nil? parsed)          (build-parse-error-response task_id elapsed result)
          (terminal-status? parsed) (core/mcp-success parsed)
          (= "polling" (:status parsed)) nil  ;; continue polling
          :else                  (build-timeout-response task_id elapsed "unknown-status"))))))

;; =============================================================================
;; Poll strategies — each is a single-purpose loop (SRP, OCP via ADT dispatch)
;; =============================================================================

(defn- jvm-poll-loop
  "Poll event journal with exponential backoff. No Emacs dependency.
   Starts at 100ms, doubles up to 2000ms ceiling."
  [task_id timeout-ms start-time]
  (let [poll-interval (atom 100)
        max-interval  2000]
    (loop []
      (if-let [ev (channel/check-event-journal task_id)]
        (build-journal-response task_id ev start-time "journal-jvm-poll")
        (let [elapsed (- (System/currentTimeMillis) start-time)]
          (if (>= elapsed timeout-ms)
            (build-timeout-response task_id elapsed "jvm-poll")
            (do (Thread/sleep @poll-interval)
                (swap! poll-interval #(min max-interval (* 2 %)))
                (recur))))))))

(defn- emacs-poll-loop
  "Emacs-backed poll loop — journal check first, then Elisp eval fallback.
   Used only when the swarm addon is confirmed available."
  [task_id timeout-ms start-time]
  (let [poll-interval (atom 500)
        max-interval  5000
        elisp-timeout 10000]
    (loop []
      (if-let [ev (channel/check-event-journal task_id)]
        (build-journal-response task_id ev start-time "channel-push-delayed")
        (let [response (poll-once task_id timeout-ms start-time elisp-timeout)]
          (if (some? response)
            response
            (let [elapsed (- (System/currentTimeMillis) start-time)]
              (if (>= elapsed timeout-ms)
                (build-timeout-response task_id elapsed "emacs-polling")
                (do (Thread/sleep @poll-interval)
                    (swap! poll-interval #(min max-interval (* 2 %)))
                    (recur))))))))))

;; =============================================================================
;; Application service — orchestrates strategy selection (DIP)
;; =============================================================================

(defn handle-swarm-collect
  "Collect task result. JVM-first: no Emacs gate.

   Phase 1 — immediate journal check (always, zero latency for headless results).
   Phase 2 — strategy dispatch via PollStrategy ADT (compile-time exhaustive).
     :strategy/emacs  → Emacs swarm addon poll (existing Emacs path, unchanged)
     :strategy/jvm    → bounded-atom backoff poll (headless / Emacs-absent)"
  [{:keys [task_id timeout_ms]}]
  (let [timeout-ms (or timeout_ms 300000)
        start-time (System/currentTimeMillis)]
    ;; Phase 1: immediate hit — bypasses all polling
    (if-let [ev (channel/check-event-journal task_id)]
      (do
        (log/info "Task" task_id "found in event journal (immediate)")
        (build-journal-response task_id ev start-time "journal-immediate"))
      ;; Phase 2: closed dispatch over PollStrategy variants
      (adt-case PollStrategy (select-strategy)
                :strategy/emacs (emacs-poll-loop task_id timeout-ms start-time)
                :strategy/jvm   (jvm-poll-loop   task_id timeout-ms start-time)))))
