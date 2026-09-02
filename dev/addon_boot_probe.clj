(ns addon-boot-probe
  "Prove the mount contract for every addon manifest on THIS JVM's classpath.

   The compliance sweep can see that a manifest exists and that its
   :addon/init-ns resolves to a file. It cannot see whether the constructor
   returns something the host can mount, because that is a runtime fact.
   This probe supplies it: scan, resolve, construct, and ask the protocol.

   Run it with the addons under test on the classpath, for example

     clojure -Sdeps '{:deps {io.github.hive-agi/lsp-mcp {:local/root \"../lsp-mcp\"}}}' \\
             -M -e '(load-file \"dev/addon_boot_probe.clj\")(addon-boot-probe/-main)'

   Load the file; do NOT put dev/ on the classpath with -M:dev. Clojure loads
   user.clj automatically and dev/user.clj auto-starts the Integrant system,
   which binds the Olympus and legacy-channel ports a running coordinator
   already holds.

   Exit code is 1 when any manifest fails to yield an IAddon."
  (:require [clojure.string :as str]
            [hive-addon.protocol :as proto]
            [hive-mcp.addons.manifest :as manifest]))

(defn- construct
  "Invoke `ctor` with an empty config, catching everything.
   Returns {:ok? true :addon v} or {:ok? false :why msg}."
  [ctor]
  (try
    {:ok? true :addon (ctor {})}
    (catch Throwable t
      {:ok? false :why (str (.getSimpleName (class t)) ": " (ex-message t))})))

(defn- load-ctor
  "Resolve the manifest's constructor, reporting WHY when it cannot be had.
   manifest/resolve-constructor rescues to nil, which cannot distinguish a
   namespace that failed to compile from one that has no such var."
  [m]
  (let [sym (symbol (str (:addon/init-ns m)) (str (:addon/init-fn m)))]
    (try
      (if-let [v (requiring-resolve sym)]
        {:ok? true :ctor v}
        {:ok? false :why (str "no var " sym)})
      (catch Throwable t
        (let [root (loop [e t] (if-let [c (.getCause e)] (recur c) e))]
          {:ok? false :why (str (.getSimpleName (class root)) ": " (ex-message root))})))))

(defn- classify
  "The verdict for one manifest: :active, or a reason it cannot mount."
  [m]
  (let [id (:addon/id m)]
    (if-let [ctor (:ctor (load-ctor m))]
      (let [{:keys [ok? addon why]} (construct ctor)]
        (cond
          (not ok?)                        {:id id :state :ctor-threw :detail why}
          (satisfies? proto/IAddon addon)  {:id id :state :active
                                            :detail (str "addon-id=" (proto/addon-id addon)
                                                         " type=" (proto/addon-type addon)
                                                         " caps=" (pr-str (proto/capabilities addon)))}
          :else                            {:id id :state :not-an-addon
                                            :detail (str "ctor returned " (pr-str (type addon)))}))
      {:id id :state :ctor-unresolved :detail (:why (load-ctor m))})))

(defn probe
  "Every classpath manifest, classified. Returns {:rows [...] :errors [...]}."
  []
  (let [{:keys [manifests errors]} (manifest/scan-classpath-manifests)]
    {:rows   (mapv classify (sort-by :addon/id manifests))
     :errors errors}))

(defn -main
  [& _]
  (let [{:keys [rows errors]} (probe)]
    (println (format "%-24s %-18s %s" "ADDON" "STATE" "DETAIL"))
    (doseq [{:keys [id state detail]} rows]
      (println (format "%-24s %-18s %s" id (name state) detail)))
    (doseq [e errors]
      (println "unreadable manifest:" (:url e) (pr-str (:errors e))))
    (println)
    (println (format "%d manifest(s) discovered, %s"
                     (count rows)
                     (pr-str (frequencies (map :state rows)))))
    (let [bad (remove #(= :active (:state %)) rows)]
      (when (seq bad)
        (println "FAILING:" (str/join ", " (map :id bad))))
      (shutdown-agents)
      (System/exit (if (seq bad) 1 0)))))
