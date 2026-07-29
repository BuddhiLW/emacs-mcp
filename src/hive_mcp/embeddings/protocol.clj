(ns hive-mcp.embeddings.protocol
  "EmbeddingProvider — `def` aliases of hive-spi.embeddings.ports.

   The protocol itself lives in hive-spi; every historical
   hive-mcp.embeddings.protocol/* name still resolves here. `satisfies?`
   must be called on the ports vars, never on these aliases."
  (:require [hive-spi.embeddings.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(do
  (def EmbeddingProvider ports/EmbeddingProvider)
  (def embed-text ports/embed-text)
  (def embed-batch ports/embed-batch)
  (def embedding-dimension ports/embedding-dimension))
