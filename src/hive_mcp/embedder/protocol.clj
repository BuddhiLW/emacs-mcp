(ns hive-mcp.embedder.protocol
  "L0 contract — embedder bounded context.

   Two narrow protocols (ISP):

   - `IEmbedder`     — text → vector. One job: produce embeddings.
   - `IProviderMeta` — provider self-describes its dim, max-tokens, key.

   Splitting these lets test/mock impls satisfy only what the caller
   needs. A locator only consults provider metadata; it never embeds.
   A write path embeds but does not introspect provider config beyond
   the resolved spec.

   Reload-safety: `defonce`-guarded per
   `hive-mcp.protocols.memory:27-30`. Without the guard, re-evaluating
   this ns re-creates the host interface class and silently invalidates
   every `defrecord`/`reify` extender compiled against the old class —
   `satisfies?` returns false, dispatch fails. The guard ensures
   `defprotocol` runs exactly once per JVM."
  (:require [clojure.string]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private -iembedder-defined? (atom false))

(when (compare-and-set! -iembedder-defined? false true)
  (defprotocol IEmbedder
    "Pure embedding capability. Implementations talk to ollama/venice/etc."

    (embed [this texts]
      "Embed `texts` (sequence of strings) into a sequence of float
       vectors of the provider's dimension. Order preserved.

       Returns a `hive-dsl.result/Result`:
         Ok  — vector of float-arrays, same length as `texts`.
         Err — `{:err/tag <kw> :err/cause <ex-or-msg>}`.")))

(defonce ^:private -iprovidermeta-defined? (atom false))

(when (compare-and-set! -iprovidermeta-defined? false true)
  (defprotocol IProviderMeta
    "Self-describing provider metadata. Read-only — no I/O."

    (provider-spec [this]
      "Return a `ProviderSpec` map describing this provider:
       `{:provider/key kw :provider/impl kw :provider/model str
         :provider/dim int :provider/max-tokens int}`.")))
