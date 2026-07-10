(ns hive-mcp.workflows.addon-smoke
  "Boot smoke-check for the hive-workflows library addons.

   Verifies — against the LIVE classpath — that the two hive-workflows addons
   are discovered via their META-INF/hive-addons manifests and initialize their
   method-dispatch strategies into the hive-mcp registries:

     - hive.workflows.strategy  -> :method strategies (dag-wave/saa/forge-belt)
     - hive.workflows.progress  -> :progress websocket + transcript wiring

   WHY a REPL check and not a cold deftest: hive-workflows is wired into the
   server ONLY via local.deps.edn (:local/root \"../hive-workflows\"); it is
   absent from deps.edn and every :test* alias. A cold `clojure -M:test` JVM
   therefore has NO workflow addon on its classpath, so the manifest scan finds
   nothing and a plain assertion would false-fail. This check must run in a JVM
   launched with the local/live classpath — i.e. the running server (7910).
   Run `(check)` there; see the (comment) runbook at the bottom.

   See also:
   - hive-mcp.addons.manifest       — the META-INF/hive-addons classpath scanner
   - hive-mcp.workflows.strategy-registry — the :method dispatch registry"
  (:require [hive-mcp.addons.manifest :as manifest]
            [hive-mcp.workflows.strategy-registry :as sr]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [hive-spi.workflow.strategy :as spi]
            [hive-dsl.result :as result]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def required-addon-ids
  "The hive-workflows library-addon manifest ids that must be discovered on the
   classpath and successfully initialized at boot."
  #{"hive.workflows.strategy" "hive.workflows.progress"})

(def required-methods
  "Dispatch methods the strategy addon must contribute to the strategy registry
   (the `:wf/default` Noop is core-seeded and intentionally not required here)."
  #{:dag-wave :saa :forge-belt})

(defn addons-on-classpath?
  "True when the hive-workflows addon manifests are on THIS JVM's classpath.
   Lets a cold test JVM (which lacks the local.deps.edn wiring) detect that the
   smoke-check cannot meaningfully run, instead of false-failing."
  []
  (some? (io/resource "META-INF/hive-addons/hive-workflows-strategy.edn")))

(defn discovered-addons
  "Scan the classpath for META-INF/hive-addons manifests and project down to the
   hive-workflows addons. Returns
     {:by-id       {addon-id -> manifest}   ;; only the required ids that are present
      :scan-total  <count of all manifests discovered>
      :scan-errors [<manifest parse/read errors>]}."
  []
  (let [scan (manifest/scan-classpath-manifests)]
    {:by-id       (into {}
                        (comp (map (juxt :addon/id identity))
                              (filter (fn [[id _]] (required-addon-ids id))))
                        (:manifests scan))
     :scan-total  (count (:manifests scan))
     :scan-errors (:errors scan)}))

(defn check
  "Run the full hive-workflows addon boot smoke-check against the live
   classpath. Returns a structured report:

     {:ok?             true|false
      :present         #{addon-ids discovered}
      :missing-addons  #{ids expected but not on classpath}
      :unresolved      #{present ids whose init constructor won't resolve}
      :methods         #{methods currently in the strategy registry}
      :missing-methods #{required methods not yet registered}
      :scan-total      <count of all manifests discovered>
      :scan-errors     [manifest scan/parse errors]
      :report          <human-readable one-line summary>}

   :ok? is true iff both addons are discovered, both init constructors resolve,
   the manifest scan produced no errors, and the strategy registry's methods are
   a superset of `required-methods`."
  []
  (let [{:keys [by-id scan-total scan-errors]} (discovered-addons)
        present      (set (keys by-id))
        missing      (set/difference required-addon-ids present)
        unresolved   (into #{} (remove #(manifest/resolve-constructor (by-id %))) present)
        methods      (set (sr/all-methods))
        missing-meth (set/difference required-methods methods)
        ok?          (and (empty? missing)
                          (empty? unresolved)
                          (empty? missing-meth)
                          (empty? scan-errors))]
    {:ok?             ok?
     :present         present
     :missing-addons  missing
     :unresolved      unresolved
     :methods         methods
     :missing-methods missing-meth
     :scan-total      scan-total
     :scan-errors     scan-errors
     :report          (if ok?
                        (format "OK: %d/%d hive-workflows addons discovered + init'd; strategy methods %s ⊇ %s"
                                (count present) (count required-addon-ids)
                                (pr-str (sort methods)) (pr-str (sort required-methods)))
                        (format "FAIL: missing-addons=%s unresolved=%s missing-methods=%s scan-errors=%d"
                                (pr-str missing) (pr-str unresolved)
                                (pr-str missing-meth) (count scan-errors)))}))

(def expected-strategy-classes
  "Required method -> the hive-workflows.method strategy record class name the
   registry must resolve it to."
  {:saa        "hive_workflows.method.SaaStrategy"
   :dag-wave   "hive_workflows.method.DagWaveStrategy"
   :forge-belt "hive_workflows.method.ForgeBeltStrategy"})

(defn check-ir-seam
  "Live check of the IR :method dispatch seam. Confirms (a) each required method
   resolves to its hive-workflows.method strategy record in the registry, and
   (b) an IR (call {:verb :method/...}) run through EvalAlgebra routes to a
   freshly-registered spy strategy. hive-workflows is reached via requiring-resolve
   so hive-mcp keeps no compile dependency on it. Returns
     {:ok? :ir-loadable? :strategy-classes-match? :routed? :routed-plan-id}."
  []
  (let [run   (requiring-resolve 'hive-workflows.algebra.eval/run)
        call* (requiring-resolve 'hive-workflows.ir/call*)]
    (if-not (and run call*)
      {:ok? false :ir-loadable? false}
      (let [classes-match? (every? (fn [[m cls]]
                                     (= cls (some-> (sr/lookup m) :strategy class .getName)))
                                   expected-strategy-classes)
            spy            (reify spi/IDispatchStrategy
                             (dispatch [_ plan _] (result/ok {:seam :hit :plan-id (:id plan)})))]
        (sr/register! :wf.smoke/ir-probe :wf-ir-probe {:strategy spy})
        (let [v (get-in (run (call* {:verb :method/wf-ir-probe :id "ir-smoke"}) {})
                        [:ok :value])]
          (sr/deregister-by-owner! :wf.smoke/ir-probe)
          {:ok?                     (and classes-match? (= :hit (:seam v)))
           :ir-loadable?            true
           :strategy-classes-match? classes-match?
           :routed?                 (= :hit (:seam v))
           :routed-plan-id          (:plan-id v)})))))

(comment
  ;; Run against the LIVE server (nREPL 7910), which has the local classpath:
  (check)
  ;; => {:ok? true :present #{"hive.workflows.strategy" "hive.workflows.progress"} ...}

  ;; Discovery half only (what META-INF/hive-addons manifests are on the path):
  (discovered-addons)

  ;; Registry half only (what :method strategies are live):
  (sort (sr/all-methods)))