(ns hive-mcp.memory.synthesis-protection
  "Synthesis afterlife: memory entries referenced by a live-enough emergent
   synthetic (:kg-synthetic node) are shielded from expiry reaping so the
   synthesis does not decay into a dangling cluster.

   The SPI reaper (hive-spi.memory.ports/cleanup-expired!) consults a provider
   fn; this namespace is that provider for hive-mcp — it alone can read the KG.
   Install once at boot via `install!`."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-spi.memory.ports :as ports]
            [taoensso.timbre :as log]))

(def ^:const protection-confidence-floor
  "Synthetic confidence at/above which its members earn afterlife. A synthetic
   below the floor is decaying toward discard and RELEASES its members back to
   normal expiry — keeping the reaper non-blocking (no immortal members, no
   deadlock). Set above the synthetics demote-confidence (0.1) and orphan
   live-ratio threshold (0.2) so a demotion reliably drops protection."
  0.3)

(defn synthesis-protected-ids
  "Set of memory-entry ids that are members of at least one synthetic whose
   confidence >= `protection-confidence-floor`. Read-only KG snapshot; never
   throws upstream — any failure yields #{} so the reaper still runs."
  []
  (try
    (let [db   (conn/db-snapshot)
          rows (conn/query '[:find (pull ?e [:kg-synthetic/members
                                             :kg-synthetic/confidence])
                             :where [?e :kg-synthetic/id]]
                           db)]
      (into #{}
            (comp (map first)
                  (filter #(>= (double (or (:kg-synthetic/confidence %) 0.0))
                               protection-confidence-floor))
                  (mapcat :kg-synthetic/members))
            rows))
    (catch Throwable e
      (log/debug "synthesis-protected-ids failed; protecting nothing:"
                 (.getMessage e))
      #{})))

(defn install!
  "Register `synthesis-protected-ids` as the memory-SPI afterlife provider.
   Idempotent (last writer wins). Returns :installed."
  []
  (ports/register-protection-provider! synthesis-protected-ids)
  (log/info "Synthesis afterlife provider installed (confidence floor"
            protection-confidence-floor ")")
  :installed)
