;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.activation
  "Activation seam for the memory piggyback drain.

   `drain-ctx` builds the ctx that `memory-piggyback/drain!` hands to the
   ranker. Without a registered provider it is exactly `{:tokens <cues>}` —
   today's behaviour byte for byte. With one, the provider's `:pins` and
   `:floor-cap` are merged in and its `:tokens` are unioned onto the cues.

   DIP: this ns knows only the extension KEY and the shape of the answer. It
   never learns what a rule is, how eligibility is decided, or that core.logic
   exists — that lives behind `:memory/activation` (see
   `hive-knowledge.activation.addon`).

   Total: a provider that throws, hangs on a bad shape, or returns garbage
   degrades to the plain cue ctx. Activation must never be able to break a tool
   response."
  (:require [clojure.set :as set]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

(def extension-key
  "Registry key an activation provider registers under. The provider is
   `(fn [activation-ctx] -> {:pins #{id} :tokens #{token} :floor-cap n})`."
  :memory/activation)

(defn- sane
  "Coerce a provider answer to the subset of keys the ranker accepts, dropping
   anything malformed. Returns nil when nothing usable survives."
  [answer]
  (when (map? answer)
    (let [pins (:pins answer)
          tokens (:tokens answer)
          cap (:floor-cap answer)]
      (cond-> nil
        (coll? pins) (assoc :pins (into #{} (filter string?) pins))
        (coll? tokens) (assoc :tokens (into #{} (filter string?) tokens))
        (and (integer? cap) (pos? cap)) (assoc :floor-cap cap)))))

(defn provider
  "The registered activation provider, or nil."
  []
  (ext/get-extension extension-key))

(defn drain-ctx
  "Drain ctx for one tool call: `{:tokens cues}` merged with whatever the
   activation provider contributes.

   `activation-ctx` is the read-only view a rule gets — tool name, the cue
   tokens harvested under `task-signal`'s allowlist, and the caller. Rules see
   the ALLOWLISTED cues only; raw tool args never reach them, so
   `task-signal/denied-arg-keys` governs activation input by construction."
  [{:keys [tool-name cues caller-id] :as _activation-ctx}]
  (let [base {:tokens (or cues #{})}]
    (if-let [f (provider)]
      (try
        (if-let [{:keys [pins tokens floor-cap]}
                 (sane (f {:tool-name tool-name :cues (or cues #{}) :caller-id caller-id}))]
          (cond-> base
            (seq tokens) (update :tokens set/union tokens)
            (seq pins) (assoc :pins pins)
            floor-cap (assoc :floor-cap floor-cap))
          base)
        (catch Throwable t
          (log/debug t "activation: provider failed; drain falls back to cues")
          base))
      base)))
