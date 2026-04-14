#!/usr/bin/env bb
;; Post-process clojure-lsp dump.edn into pre-extracted focused files.
;;
;; Reads the monolithic dump.edn (50-60MB) and writes smaller focused files:
;;   var-defs.edn    — all var definitions (~2MB)
;;   call-graph.edn  — call edges (~5MB)
;;   ns-graph.edn    — namespace dep graph (~1MB)
;;   summary.edn     — counts and metadata
;;
;; This avoids the 40+ minute EDN parse on the host JVM side.
;; bb parses 57MB EDN in ~10-15 seconds.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn file-uri? [uri]
  (and (string? uri)
       (.startsWith ^String uri "file://")))

(defn extract-var-definitions
  "Extract var definitions from analysis map, project files only."
  [analysis]
  (into []
        (comp
         (filter (fn [[uri _]] (file-uri? uri)))
         (mapcat (fn [[uri buckets]]
                   (map (fn [vd]
                          {:ns         (:ns vd)
                           :name       (:name vd)
                           :file       uri
                           :row        (:row vd)
                           :col        (:col vd)
                           :end-row    (:end-row vd)
                           :end-col    (:end-col vd)
                           :arglists   (or (:arglist-strs vd) [])
                           :private?   (boolean (:private vd))
                           :macro?     (boolean (:macro vd))
                           :doc        (:doc vd)
                           :defined-by (:defined-by vd)})
                        (:var-definitions buckets)))))
        analysis))

(defn extract-call-graph
  "Extract call edges from var-usages, project files only."
  [analysis]
  (into []
        (comp
         (filter (fn [[uri _]] (file-uri? uri)))
         (mapcat (fn [[uri buckets]]
                   (into []
                         (comp
                          (filter :from-var)
                          (map (fn [vu]
                                 {:caller-ns (:from vu)
                                  :caller-fn (:from-var vu)
                                  :callee-ns (:to vu)
                                  :callee-fn (:name vu)
                                  :file      uri
                                  :row       (:row vu)})))
                         (:var-usages buckets)))))
        analysis))

(defn extract-ns-defs
  "Extract namespace definitions from analysis map."
  [analysis]
  (into []
        (comp
         (filter (fn [[uri _]] (file-uri? uri)))
         (mapcat (fn [[_uri buckets]]
                   (:namespace-definitions buckets))))
        analysis))

(let [cache-path (first *command-line-args*)
      _ (when-not cache-path
          (println "Usage: extract.bb <cache-path>")
          (System/exit 1))
      dump-path (str cache-path "/dump.edn")
      _ (when-not (.exists (io/file dump-path))
          (println "No dump.edn at" dump-path)
          (System/exit 1))

      start (System/currentTimeMillis)
      _ (println "Parsing" dump-path "...")
      raw (edn/read-string (slurp dump-path))
      parse-ms (- (System/currentTimeMillis) start)
      _ (println "Parsed in" parse-ms "ms")

      analysis (:analysis raw)
      dep-graph (:dep-graph raw)

      ;; Extract focused data
      var-defs (extract-var-definitions analysis)
      call-graph (extract-call-graph analysis)
      ns-defs (extract-ns-defs analysis)

      ;; Write focused files
      _ (spit (str cache-path "/var-defs.edn") (pr-str var-defs))
      _ (spit (str cache-path "/call-graph.edn") (pr-str call-graph))
      _ (spit (str cache-path "/ns-graph.edn") (pr-str dep-graph))
      _ (spit (str cache-path "/ns-defs.edn") (pr-str ns-defs))

      extract-ms (- (System/currentTimeMillis) start (- parse-ms))

      summary {:var-defs-count (count var-defs)
               :call-graph-count (count call-graph)
               :ns-count (count dep-graph)
               :ns-defs-count (count ns-defs)
               :parse-ms parse-ms
               :extract-ms extract-ms
               :total-ms (- (System/currentTimeMillis) start)}
      _ (spit (str cache-path "/summary.edn") (pr-str summary))]

  (println "Extraction complete:" summary))
