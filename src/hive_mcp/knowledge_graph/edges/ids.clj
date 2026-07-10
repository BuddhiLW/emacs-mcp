(ns hive-mcp.knowledge-graph.edges.ids
  "Edge id generation. Leaf slice of the edges façade — no edge-graph deps.")

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn generate-edge-id
  "Generate a unique edge ID."
  []
  (str (random-uuid)))
