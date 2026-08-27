(ns hive-mcp.scripts.aot-boot
  "AOT-compile the addon boot closure into target/boot-classes.

   Run with hive-mcp STOPPED:
     clj -Sdeps \"$(cat local.deps.edn)\" -X:aot-boot

   Then boot with the classes on the classpath:
     HIVE_FAST_BOOT=1 bin/hive-mcp

   Rationale lives in hive memory (KG-linked), not here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def out-dir "target/boot-classes")

(def stamp-file (str out-dir "/.aot-stamp.edn"))

(def ^:private protocol-namespaces
  "Compiled BEFORE the addons, never merely required. Addons `reify` these
   protocols, so an AOT'd addon class names the protocol's interface; `compile`
   skips an already-loaded namespace, so requiring one here would leave its
   interface runtime-generated and every addon class referencing it unloadable."
  '[malli.core
    hive-addon.protocol
    hive-mcp.addons.protocol
    hive-mcp.addons.terminal
    hive-mcp.protocols.vessel])

(defn ctor-namespaces
  "Addon constructor namespaces discovered from the classpath manifests."
  []
  (let [discover (requiring-resolve 'hive-addon.mount.boundary/discover-specs)
        solve    (requiring-resolve 'hive-addon.mount.solve/solve)
        specs    (:specs (discover))]
    (into [] (comp (map :addon/init-ns) (map symbol))
          (:ordered (solve specs)))))

(defn- newest-source-mtime
  "Newest mtime across every source root on the classpath, so a boot can tell
   whether these classes still describe the tree."
  []
  (->> (str/split (System/getProperty "java.class.path") #":")
       (map io/file)
       (filter #(.isDirectory %))
       (mapcat file-seq)
       (filter #(re-find #"\.cljc?$" (.getName %)))
       (map #(.lastModified %))
       (reduce max 0)))

(defn- compile-one
  [ns-sym]
  (let [started (System/nanoTime)]
    (try
      (compile ns-sym)
      {:ns ns-sym :ok? true :ms (/ (- (System/nanoTime) started) 1e6)}
      (catch Throwable t
        {:ns ns-sym :ok? false :ms (/ (- (System/nanoTime) started) 1e6)
         :error (ex-message t)}))))

(defn- report!
  [results]
  (doseq [{:keys [ns ok? ms error]} (reverse (sort-by :ms results))]
    (println (format "%-32s %9.1f ms %s" ns ms (if ok? "" (str "FAILED " error)))))
  (println (format "\n%d/%d compiled into %s"
                   (count (filter :ok? results)) (count results) out-dir)))

(defn- write-stamp!
  [results]
  (spit stamp-file
        (pr-str {:compiled     (mapv :ns results)
                 :source-mtime (newest-source-mtime)})))

(defn compile-boot-closure!
  "AOT every addon constructor namespace and its transitive closure.

   The stamp is written only when every namespace compiled, and the exit code
   says the same. `bin/hive-mcp` trusts the stamp, and a partial image is one
   whose protocol interfaces are half runtime-generated — the single state in
   which an AOT'd addon class cannot find the interface it names."
  [_]
  (.mkdirs (io/file out-dir))
  (let [results (binding [*compile-path* out-dir]
                  (let [protocols (mapv compile-one protocol-namespaces)]
                    (into protocols (map compile-one) (ctor-namespaces))))
        clean?  (every? :ok? results)]
    (report! results)
    (when clean? (write-stamp! results))
    (shutdown-agents)
    (System/exit (if clean? 0 1))))
