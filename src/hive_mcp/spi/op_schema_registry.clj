(ns hive-mcp.spi.op-schema-registry
  "Owner-tracking router for addon-contributed op-schema bundles into the
   shared hive-spi core-op registry (hive-spi.schema.registry).

   Addons contribute malli op-arg schemas AS DATA via IAddon `hooks` under the
   `:op-schema/*` keyword namespace; `hive-mcp.addons.core` routes each entry
   here. The hook value is a {schema-key malli-form} bundle — the exact shape
   `hive-spi.schema.registry/register-all!` consumes. Ownership (addon id) is
   tracked so `deregister-by-owner!` removes exactly the keys an addon added,
   never clobbering another addon's schemas.

   Mirrors hive-mcp.plan.field-registry / hive-mcp.saa.registry: a generic core
   router an addon populates and cleans up by owner. hive-mcp core names ZERO
   concrete op-schema keys — the addon owns its field schemas (reverted-9 rule);
   core stays field-agnostic and merely forwards the bundle to hive-spi."
  (:require [hive-spi.schema.registry :as reg]
            [clojure.tools.logging :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; owner (addon-id) -> #{schema-key ...} that owner contributed
(defonce ^:private owners* (atom {}))

(defn register-by-key!
  "IAddon `hooks` entry point. `owner` is the addon id; `k` the `:op-schema/*`
   hook key (accepted for hook-walk symmetry); `bundle` a {schema-key
   malli-form} map. Registers the bundle into the hive-spi core-op registry and
   records its keys under `owner` for per-owner teardown. Idempotent per key
   (last-write-wins). Returns the vec of registered keys, or nil for a malformed
   (non-map) bundle."
  [owner k bundle]
  (if (map? bundle)
    (let [ks (reg/register-all! bundle)]
      (swap! owners* update owner (fnil into #{}) ks)
      ks)
    (do (log/warn "[op-schema-registry] non-map bundle for hook key — skipping"
                  {:owner owner :key k :bundle bundle})
        nil)))

(defn deregister-by-owner!
  "Remove every op-schema `owner` contributed (addon shutdown). Deregisters the
   owner's keys from the hive-spi registry and forgets the ownership record.
   Idempotent. Returns the vec of removed keys."
  [owner]
  (let [ks (get @owners* owner)]
    (when (seq ks)
      (reg/deregister-all! ks))
    (swap! owners* dissoc owner)
    (vec ks)))

(defn owned
  "Current {owner #{schema-key}} ownership map. Introspection / tests."
  []
  @owners*)

(defn reset-for-test!
  "Forget all ownership records (does NOT touch the hive-spi registry). Test-only."
  []
  (reset! owners* {}))
