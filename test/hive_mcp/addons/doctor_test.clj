(ns hive-mcp.addons.doctor-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-dsl.result :as r]
            [hive-mcp.addons.doctor :as doctor])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- fake-addon
  [id addon-type capabilities health]
  (reify addon/IAddon
    (addon-id [_] id)
    (addon-type [_] addon-type)
    (capabilities [_] capabilities)
    (initialize! [_ _] {:success? true})
    (shutdown! [_] nil)
    (tools [_] [])
    (schema-extensions [_] [])
    (health [_] health)
    (excluded-tools [_] #{})
    (hooks [_] {})))

(defn- spec
  ([] (spec #{}))
  ([features]
   (cond-> {:addon/id "hive.emacs"
            :addon/type :native
            :addon/init-ns "hive-emacs.addon"
            :addon/init-fn "addon-ctor"
            :addon/capabilities #{:tools :editor}
            :addon/dependencies #{}
            :addon/requires-capabilities #{}}
     (seq features) (assoc :addon/doctor {:emacs/features features}))))

(defn- healthy-ports
  [manifest]
  (let [instance (fake-addon "hive.emacs" :native
                             #{:tools :editor}
                             {:status :ok :details {:emacs-running? true}})]
    {:discover-fn #(hash-map :specs [manifest] :errors [])
     :resolve-constructor-fn (constantly (fn [_] instance))
     :scan-project-fn (constantly {:status :pass
                                   :evidence {:scan-errors []}})
     :get-entry-fn (fn [id]
                     (when (= id "hive.emacs")
                       {:addon instance
                        :state :active
                        :registered-at (Instant/parse "2026-07-21T00:00:00Z")
                        :init-time (Instant/parse "2026-07-21T00:00:01Z")
                        :init-result {:success? true}}))
     :emacs-eval-fn (fn [_code _timeout]
                      {:success true :result "t"})
     :now-fn #(Instant/parse "2026-07-21T00:00:02Z")}))

(defn- stage-by-name
  [report stage-name]
  (first (filter #(= stage-name (:stage %)) (:stages report))))

(deftest healthy-doctor-report-is-complete-and-valid
  (let [manifest (spec #{"hive-mcp" "hive-mcp-cider"})
        result (doctor/run-doctor {:addon-id "hive.emacs"
                                   :directory "/tmp/hive-emacs"
                                   :timeout-ms 1500}
                                  (healthy-ports manifest))
        report (:ok result)]
    (is (r/ok? result))
    (is (doctor/report-valid? report))
    (is (:ok? report))
    (is (= 6 (count (:stages report))))
    (is (= {:pass 6 :fail 0 :skip 0} (:summary report)))
    (is (every? #(= :pass (:status %)) (:stages report)))
    (is (= ["hive-mcp" "hive-mcp-cider"]
           (get-in report [:request :emacs-features])))))

(deftest findings-stay-in-a-successful-evidence-envelope
  (let [manifest (spec #{"hive-mcp"})
        instance (fake-addon "hive.emacs" :native #{:tools}
                             {:status :degraded})
        ports (assoc (healthy-ports manifest)
                     :get-entry-fn (constantly {:addon instance
                                                :state :active
                                                :init-result {:success? true}})
                     :scan-project-fn (constantly
                                       {:status :fail
                                        :evidence
                                        {:forbidden-dependencies
                                         [{:lib "io.github.hive-agi/hive-mcp"}]}})
                     :emacs-eval-fn (fn [_ _]
                                      {:success true :result "nil"}))
        result (doctor/run-doctor {:addon-id "hive.emacs"
                                   :directory "/tmp/hive-emacs"}
                                  ports)
        report (:ok result)]
    (testing "diagnostic findings are report data, not an MCP execution error"
      (is (r/ok? result))
      (is (false? (:ok? report))))
    (is (= :fail (:status (stage-by-name report :dependency-boundary))))
    (is (= :fail (:status (stage-by-name report :lifecycle-smoke))))
    (is (= :fail (:status (stage-by-name report :capability-comparison))))
    (is (= :fail (:status (stage-by-name report :emacs-features))))))

(deftest absent-emacs-expectations-are-an-explicit-skip
  (let [manifest (spec)
        result (doctor/run-doctor {:addon-id "hive.emacs"}
                                  (healthy-ports manifest))
        report (:ok result)]
    (is (r/ok? result))
    (is (:ok? report) "skips do not make an otherwise healthy report fail")
    (is (= :skip (:status (stage-by-name report :emacs-features))))
    (is (= 1 (get-in report [:summary :skip])))))

(deftest explicit-emacs-features-override-manifest-hints
  (let [manifest (spec #{"manifest-feature"})
        seen (atom [])
        ports (assoc (healthy-ports manifest)
                     :emacs-eval-fn
                     (fn [code _]
                       (swap! seen conj code)
                       {:success true :result "t"}))
        result (doctor/run-doctor {:addon-id "hive.emacs"
                                   :emacs-features ["requested-feature"]}
                                  ports)]
    (is (r/ok? result))
    (is (= ["(featurep 'requested-feature)"] @seen))))

(deftest opaque-live-health-details-are-folded-to-json-safe-evidence
  (let [manifest (spec)
        instance (fake-addon "hive.emacs" :native #{:tools :editor}
                             {:status :ok :details {:opaque (Object.)}})
        ports (assoc (healthy-ports manifest)
                     :get-entry-fn
                     (constantly {:addon instance
                                  :state :active
                                  :init-result {:success? true}}))
        report (:ok (doctor/run-doctor {:addon-id "hive.emacs"} ports))]
    (is (:ok? report))
    (is (string? (get-in (stage-by-name report :lifecycle-smoke)
                         [:evidence :health :details :opaque])))))

(deftest invalid-input-is-a-domain-error
  (let [result (doctor/run-doctor {:addon-id ""
                                   :emacs-features ["bad feature"]})]
    (is (r/err? result))
    (is (= :addon-doctor/invalid-input (:error result)))))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq root))]
    (.delete ^java.io.File f)))

(defn- with-temp-project
  [deps-content source-content f]
  (let [root (.toFile (Files/createTempDirectory
                       "addon-doctor-"
                       (make-array FileAttribute 0)))
        source-dir (java.io.File. root "src/demo")]
    (try
      (.mkdirs source-dir)
      (spit (java.io.File. root "deps.edn") deps-content)
      (spit (java.io.File. source-dir "addon.clj") source-content)
      (f root)
      (finally
        (delete-tree! root)))))

(deftest project-boundary-scan-proves-clean-and-host-coupled-projects
  (testing "host-neutral addon passes"
    (with-temp-project
      "{:deps {io.github.hive-agi/hive-addon {:mvn/version \"0.3.1\"}}}"
      "(ns demo.addon (:require [hive-addon.protocol :as addon]))"
      (fn [root]
        (let [result (doctor/scan-project-boundary (.getPath root))]
          (is (= :pass (:status result)))
          (is (empty? (get-in result [:evidence :scan-errors])))))))
  (testing "direct host coordinate and namespace are both evidence"
    (with-temp-project
      "{:deps {io.github.hive-agi/hive-mcp {:mvn/version \"1.0.0\"}}}"
      "(ns demo.addon (:require [hive-mcp.addons.core :as core]))"
      (fn [root]
        (let [result (doctor/scan-project-boundary (.getPath root))]
          (is (= :fail (:status result)))
          (is (= "io.github.hive-agi/hive-mcp"
                 (get-in result [:evidence :forbidden-dependencies 0 :lib])))
          (is (= "hive-mcp.addons.core"
                 (get-in result
                         [:evidence :forbidden-source-namespaces 0 :namespace]))))))))
