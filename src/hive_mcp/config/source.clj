(ns hive-mcp.config.source
  "Port: WHERE configuration comes from.

   Production reads a disk-backed atom. A test must not — config on disk is a
   live, shared, user-owned system, and a test that reads it asserts against
   whatever the developer last tuned. Binding a StaticConfigSource makes a test
   depend on the config it declares, and on nothing else."
  (:require [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IConfigSource
  "One question: what is the effective config map?"
  (-config [this] "The effective config map."))

(defprotocol IMutableConfigSource
  "A source that writes can land in. Segregated from IConfigSource (ISP): a
   read-only source simply does not implement it, so a write against one fails
   loudly instead of silently going somewhere else."
  (-config-atom [this] "The atom writes mutate."))

(defrecord AtomConfigSource [config-atom defaults]
  IConfigSource
  (-config [_] (or @config-atom defaults))
  IMutableConfigSource
  (-config-atom [_] config-atom))

(defrecord StaticConfigSource [config]
  IConfigSource
  (-config [_] config))

(defrecord EmptyConfigSource []
  IConfigSource
  (-config [_] {}))

(defn atom-source
  "The production source: a disk-loaded atom, falling back to defaults."
  [config-atom defaults]
  (->AtomConfigSource config-atom defaults))

(defn static-source
  "A fixed config map — the test default. Give it an EDN map and the code under
   test sees exactly that, no matter what is on disk."
  [config]
  (->StaticConfigSource config))

(defn empty-source
  "The no-op source: no config at all. Every lookup misses, so a caller that
   silently depends on ambient config fails visibly instead of picking up the
   developer's machine."
  []
  (->EmptyConfigSource))

(m/=> static-source [:=> [:cat :map] [:fn #(satisfies? IConfigSource %)]])

(m/=> empty-source [:=> [:cat] [:fn #(satisfies? IConfigSource %)]])