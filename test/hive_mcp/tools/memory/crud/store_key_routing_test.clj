(ns hive-mcp.tools.memory.crud.store-key-routing-test
  "Verify `:store-key` threads through do-add! / index-entry! /
   finalize-entry! and routes the IMemoryStore lookup to the correct
   registry slot. Backward-compat: omitted :store-key defaults to
   `:default` (legacy / milvus path).

   Regression class: a future refactor that drops the `:store-key`
   destructure from any of the three private fns would silently route
   kanban writes back to the milvus :default slot — exactly the
   coupling this plan exists to undo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-mcp.crystal.recall]
            [hive-mcp.knowledge-graph.connection]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.memory.crud.write :as wr]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- recorder-stub
  "IMemoryStore stub that records every store-key the registry hands
   it via the `lookups` atom (keyed by op + slot tag in entry)."
  [slot-tag lookups]
  (reify proto/IMemoryStore
    (connect!       [_ _] {:success? true})
    (disconnect!    [_]   nil)
    (connected?     [_]   true)
    (health-check   [_]   {:healthy? true})
    (add-entry!     [_ e]
      (swap! lookups update :adds (fnil conj []) [slot-tag (:type e)])
      (str "id-" slot-tag "-" (random-uuid)))
    (get-entry      [_ id] {:id id :slot slot-tag})
    (update-entry!  [_ id _]
      (swap! lookups update :updates (fnil conj []) [slot-tag id])
      {:success? true :id id :slot slot-tag})
    (delete-entry!  [_ _] nil)
    (query-entries  [_ _] [])
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status   [_] {:slot slot-tag})
    (reset-store!   [_] nil)))

(use-fixtures :each
  (fn [f]
    (let [snapshot (proto/registered-stores)]
      (proto/unregister-store! :default)
      (proto/unregister-store! :kanban)
      (try
        (f)
        (finally
          (proto/unregister-store! :default)
          (proto/unregister-store! :kanban)
          (doseq [[k store] snapshot]
            (when (#{:default :kanban} k)
              (proto/register-store! k store))))))))

(deftest do-add-default-store-key-routes-to-default-slot
  (let [lookups (atom {})]
    (proto/register-store! :default (recorder-stub :default lookups))
    (proto/register-store! :kanban  (recorder-stub :kanban  lookups))
    ;; Stub out the deeper KG + channel paths so do-add!'s body
    ;; reaches add-entry! without external dependencies.
    (with-redefs [hive-mcp.knowledge-graph.connection/with-tx-batch
                  (fn [body] (eval body))
                  hive-mcp.crystal.recall/register-created-id!
                  (constantly nil)]
      (let [_ (#'wr/do-add! {:type "note" :content "ping" :tags ["t"]
                              :directory "/tmp"})
            adds (:adds @lookups)]
        (is (some #(= :default (first %)) adds)
            "default :store-key routed to :default slot")
        (is (not (some #(= :kanban (first %)) adds))
            "default :store-key did NOT touch :kanban slot")))))

(deftest do-add-explicit-kanban-store-key-routes-to-kanban-slot
  (let [lookups (atom {})]
    (proto/register-store! :default (recorder-stub :default lookups))
    (proto/register-store! :kanban  (recorder-stub :kanban  lookups))
    (with-redefs [hive-mcp.knowledge-graph.connection/with-tx-batch
                  (fn [body] (eval body))
                  hive-mcp.crystal.recall/register-created-id!
                  (constantly nil)]
      (let [_ (#'wr/do-add! {:type "note" :content "kanban-ping" :tags ["kanban" "todo"]
                              :directory "/tmp"
                              :store-key :kanban})
            adds (:adds @lookups)]
        (is (some #(= :kanban (first %)) adds)
            ":store-key :kanban routed to :kanban slot")
        (is (not (some #(= :default (first %)) adds))
            ":store-key :kanban did NOT touch :default slot")))))

(deftest index-entry-default-store-key-uses-default-slot
  (let [lookups (atom {})]
    (proto/register-store! :default (recorder-stub :default lookups))
    (proto/register-store! :kanban  (recorder-stub :kanban  lookups))
    (#'wr/index-entry! {:type "note" :content "x" :tags-with-scope []
                        :content-hash "h" :duration-str "long"
                        :expires nil :project-id "p"
                        :abstraction-level 1 :knowledge-gaps []})
    (is (= [[:default "note"]] (:adds @lookups)))))

(deftest index-entry-kanban-store-key-uses-kanban-slot
  (let [lookups (atom {})]
    (proto/register-store! :default (recorder-stub :default lookups))
    (proto/register-store! :kanban  (recorder-stub :kanban  lookups))
    (#'wr/index-entry! {:type "note" :content "x" :tags-with-scope ["kanban"]
                        :content-hash "h" :duration-str "long"
                        :expires nil :project-id "p"
                        :abstraction-level 1 :knowledge-gaps []
                        :store-key :kanban})
    (is (= [[:kanban "note"]] (:adds @lookups)))))
