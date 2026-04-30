(ns hive-mcp.embeddings.init
  "Addon manifest for the embeddings sub-system.

   Today this manifest only owns the Venice provider bridge: when the
   embeddings.venice ns is on the classpath, `register-venice!` flips a
   factory into the embedding registry so `:venice` configs resolve.

   Wired declaratively via `hive-di.addon/defmanifest` so future
   embedding extensions (more providers, alt impls) layer on without
   touching `embeddings.registry/init!`."
  (:require [hive-di.addon :as addon]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(addon/defmanifest manifest
  {:bridges [{:ns      'hive-mcp.embeddings.registry
              :install 'hive-mcp.embeddings.registry/register-venice!}]})
