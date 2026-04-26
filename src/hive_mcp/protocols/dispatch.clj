(ns hive-mcp.protocols.dispatch
  "Polymorphic dispatch context protocol for agent communication."
  (:require [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IDispatchContext Protocol
;;; ============================================================================

(defprotocol IDispatchContext
  "Protocol for resolving task context for agent dispatch."

  (resolve-context [this]
    "Return a consumable map with at minimum {:prompt string}.")

  (context-type [this]
    "Return keyword identifying the context type."))

;;; ============================================================================
;;; TextContext Record (OSS Implementation)
;;; ============================================================================

(defrecord TextContext [prompt]
  IDispatchContext
  (resolve-context [_] {:prompt prompt})
  (context-type [_] :text))

;;; ============================================================================
;;; RefContext Record (Pass-by-Reference + Compressed)
;;; ============================================================================

(defrecord RefContext [prompt ctx-refs kg-node-ids scope reconstruct-fn]
  ;; prompt         - Base task prompt string (always present for fallback)
  ;; ctx-refs       - Map of category->ctx-id for context-store lookups
  ;;                  e.g. {:axioms "ctx-123" :conventions "ctx-456"}
  ;; kg-node-ids    - Vector of node IDs for context resolution
  ;; scope          - Project scope for context resolution (e.g. "hive-mcp")
  ;; reconstruct-fn - Function (fn [ctx-refs kg-node-ids scope] -> string)
  ;;                   that reconstructs compressed context from references.
  ;;                   Injected at creation time to avoid protocol coupling.
  IDispatchContext
  (resolve-context [_]
    (let [reconstructed (when reconstruct-fn
                          (try
                            (reconstruct-fn ctx-refs kg-node-ids scope)
                            (catch Throwable e
                              (str "Context reconstruction failed: " (.getMessage e)))))]
      (cond-> {:prompt prompt}
        (seq ctx-refs)      (assoc :refs (vec (vals ctx-refs)))
        (seq kg-node-ids)   (assoc :kg-nodes kg-node-ids)
        reconstructed       (assoc :reconstructed reconstructed
                                   :prompt (str reconstructed "\n\n---\n\n" prompt)))))
  (context-type [_] :ref))

;;; ============================================================================
;;; Factory Functions
;;; ============================================================================

(defn ->text-context
  "Create a TextContext wrapping a plain text prompt."
  [prompt]
  (->TextContext prompt))

(defn ->ref-context
  "Create a RefContext for pass-by-reference dispatch."

  [prompt {:keys [ctx-refs kg-node-ids scope reconstruct-fn]}]
  (let [;; Default reconstruct-fn: resolve lazily to avoid circular deps.
        ;; Uses requiring-resolve to load hive-mcp.context.reconstruction
        ;; at call time, not at require time.
        effective-fn (or reconstruct-fn
                         (fn [refs nodes sc]
                           (when-let [f (rescue nil (requiring-resolve
                                                     'hive-mcp.context.reconstruction/reconstruct-context))]
                             (f refs nodes sc))))]
    (->RefContext prompt
                  (or ctx-refs {})
                  (vec (or kg-node-ids []))
                  scope
                  effective-fn)))

(defn ->graph-context
  "Create an extension-backed dispatch context."

  [task-node-id kg-store]
  (if-let [f (rescue nil
               (when-let [get-ext (requiring-resolve 'hive-mcp.extensions.registry/get-extension)]
                 (get-ext :dp/graph-ctx)))]
    (f task-node-id kg-store)
    (->TextContext (str "Extension context unavailable. Task node: " task-node-id))))

;;; ============================================================================
;;; Backward Compatibility
;;; ============================================================================

(defn ensure-context
  "Coerce a string or IDispatchContext into an IDispatchContext."
  [prompt-or-context]
  (if (satisfies? IDispatchContext prompt-or-context)
    prompt-or-context
    (->TextContext (str prompt-or-context))))
