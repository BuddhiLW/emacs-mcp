(ns hive-mcp.tools.swarm.core
  "Core utilities for swarm tool handlers including response builders and swarm availability check."
  (:require [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.dns.validation :as v]
            [clojure.data.json :as json]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn swarm-addon-available?
  "Check if hive-mcp-swarm addon is loaded in Emacs."
  []
  (let [{:keys [success result timed-out]} (ec/eval-elisp-with-timeout "(featurep 'hive-mcp-swarm)" 2000)]
    (and success (not timed-out) (= result "t"))))

(defn mcp-success
  "Build a successful MCP response."
  [data]
  {:type "text" :text (if (string? data) data (json/write-str data))})

(defn mcp-error
  "Build an error MCP response."
  [message]
  {:type "text" :text message :isError true})

(defn mcp-error-json
  "Build an error MCP response with JSON payload."
  [data]
  {:type "text" :text (json/write-str data) :isError true})

(defn mcp-timeout-error
  "Build a timeout error response."
  [operation & {:keys [extra-data]}]
  (let [base {:error (str operation " timed out")
              :status "timeout"}
        data (if extra-data (merge base extra-data) base)]
    {:type "text" :text (json/write-str data) :isError true}))

(defn addon-not-loaded-error
  "Return standard error when swarm addon not loaded."
  []
  {:type "text" :text "hive-mcp-swarm addon not loaded. Run (require 'hive-mcp-swarm)" :isError true})

(defmacro with-swarm
  "Execute body only if swarm addon is available."
  [& body]
  `(if (swarm-addon-available?)
     (do ~@body)
     (addon-not-loaded-error)))

(defn eval-elisp-safe
  "Evaluate elisp with timeout and structured response handling."
  [elisp timeout-ms]
  (let [{:keys [success result error timed-out]} (ec/eval-elisp-with-timeout elisp timeout-ms)]
    (cond
      timed-out {:ok false :error "Elisp evaluation timed out" :timed-out true}
      (not success) {:ok false :error error :timed-out false}
      :else {:ok true :result result})))

(defn format-elisp-list
  "Format a Clojure sequence as elisp list."
  [items format-item-fn]
  (when (seq items)
    (format "'(%s)" (str/join " " (map format-item-fn items)))))

(defn escape-for-elisp
  "Escape a string for safe embedding in elisp."
  [s]
  (v/escape-elisp-string s))
