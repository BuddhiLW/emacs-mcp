(ns hive-mcp.events.context
  "Pure context manipulation helpers for the event system.

   Context shape: {:coeffects {...} :effects {...} :queue [...] :stack [...]}

   Zero-dependency utilities used by interceptors and handlers to read
   and write the :coeffects and :effects maps.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn get-coeffect
  "Get a coeffect value from context."
  ([context]
   (:coeffects context))
  ([context key]
   (get-in context [:coeffects key]))
  ([context key not-found]
   (get-in context [:coeffects key] not-found)))

(defn assoc-coeffect
  "Associate a coeffect value in context."
  [context key value]
  (assoc-in context [:coeffects key] value))

(defn update-coeffect
  "Update a coeffect value in context."
  [context key f & args]
  (apply update-in context [:coeffects key] f args))

(defn get-effect
  "Get an effect value from context."
  ([context]
   (:effects context))
  ([context key]
   (get-in context [:effects key]))
  ([context key not-found]
   (get-in context [:effects key] not-found)))

(defn assoc-effect
  "Associate an effect value in context."
  [context key value]
  (assoc-in context [:effects key] value))

(defn update-effect
  "Update an effect value in context."
  [context key f & args]
  (apply update-in context [:effects key] f args))
