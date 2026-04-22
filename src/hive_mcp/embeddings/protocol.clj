(ns hive-mcp.embeddings.protocol
  "Canonical home for the EmbeddingProvider protocol.

   Layering rationale:
     Embedder implementations (ollama, openai, openrouter) should not depend
     on a vectordb backend (hive-mcp.chroma.*) to know what protocol to
     implement. This namespace is a pure protocol declaration with no
     dependencies — any vectordb backend or downstream consumer can depend
     on it, not the other way around.

   Consumers: hive-mcp.chroma.embeddings (re-exports for backward compat),
   hive-mcp.vectordb.facade, etc.

   Implementers: hive-mcp.embeddings.{ollama,openai,openrouter}, test doubles.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol EmbeddingProvider
  "Protocol for generating text embeddings."
  (embed-text [this text]
    "Generate embedding vector for text.")
  (embed-batch [this texts]
    "Generate embeddings for multiple texts.")
  (embedding-dimension [this]
    "Return the dimension of embeddings produced."))
