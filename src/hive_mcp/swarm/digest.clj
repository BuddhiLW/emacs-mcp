(ns hive-mcp.swarm.digest
  "Compact swarm status rows projected from the hivemind shout ring.

   The counterpart to hive-mcp.channel.audience. Once a shout only reaches its
   spawner and a burst collapses to one row, nobody's context carries the
   running picture any more — so the picture has to be PULLABLE. This is that
   projection: one row per agent, cheap enough to ask for on demand and small
   enough that asking is not micromanagement.

   Pure. The caller supplies `now` and a snapshot of the registry map; nothing
   here reads a clock or an atom."
  (:require [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-message-cap
  "Characters of the last message a row carries. A status row is a glance, not
   a transcript — the transcript store already holds the full exchange."
  200)

(defn- clip
  [s cap]
  (let [s (str s)]
    (if (<= (count s) cap) s (str (subs s 0 cap) "…"))))

(defn- highest-turn
  "Highest :turn any shout in the ring carried, or nil when none did.
   bb-ling stamps it (hive-agent.loop.bb-agentic/wrap-llm-fn); other producers
   need not, and their rows simply report no turn."
  [messages]
  (let [turns (->> messages (keep #(get-in % [:data :turn])) (filter number?))]
    (when (seq turns) (apply max turns))))

(defn agent-row
  "Project one agent's shout ring into a status row. Pure.

   Returns nil when the ring holds no shouts — an agent that has never spoken
   has no status to report here (it is still in the DataScript roster).

   Row keys: :a agent, :e last event, :m last message (clipped), :t task,
   :turn highest turn seen, :parent spawner, :idle-s seconds since the last
   shout, :shouts ring depth."
  ([now agent-id entry] (agent-row now agent-id entry default-message-cap))
  ([now agent-id entry cap]
   (let [{:keys [messages]} (:data entry)
         messages (vec messages)]
     (when (seq messages)
       (let [latest (peek messages)
             ts (or (:timestamp latest) 0)
             turn (highest-turn messages)]
         (cond-> {:a agent-id
                  :e (some-> (:event-type latest) name)
                  :shouts (count messages)
                  :idle-s (max 0 (quot (- now ts) 1000))}
           (:message latest) (assoc :m (clip (:message latest) cap))
           (:task latest) (assoc :t (clip (:task latest) cap))
           turn (assoc :turn turn)
           (:parent-id latest) (assoc :parent (:parent-id latest))))))))

(defn roster
  "Project the whole shout registry into status rows, most recently active
   first. Pure — `registry` is a snapshot of {agent-id {:data {:messages ...}}}.

   `:only-children-of` keeps just the rows whose :parent is that agent, which
   is how a coordinator asks 'how are MY lings doing' without seeing a peer's."
  ([now registry] (roster now registry nil))
  ([now registry {:keys [only-children-of message-cap]}]
   (let [cap (or message-cap default-message-cap)
         rows (keep (fn [[agent-id entry]]
                      (agent-row now agent-id entry cap))
                    registry)
         rows (if only-children-of
                (filter #(= (str only-children-of) (str (:parent %))) rows)
                rows)]
     (vec (sort-by :idle-s rows)))))

(defn render
  "Render roster rows as one line each — the form a human or an LLM reads
   fastest. Pure."
  [rows]
  (str/join "\n"
            (map (fn [{:keys [a e turn idle-s m]}]
                   (str a
                        " [" (or e "?") "]"
                        (when turn (str " turn=" turn))
                        " idle=" idle-s "s"
                        (when m (str " — " m))))
                 rows)))
