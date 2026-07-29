(ns hive-mcp.test.stub.txlog-ops
  "Driver-free ITxlogOps stub for storage-recovery tests.

   Classifies a segment from its bytes instead of asking datalevin:
     empty file          => strict scan succeeds        (:ok)
     all-zero bytes      => strict fails, lenient       (:tail-zeroed)
     any non-zero byte   => strict and lenient fail     (:unhealable)

   `truncate-partial-tail!` really trims the file so callers can assert the
   on-disk result and the returned size record.

   API:
     (->stub)                 fresh stub
     (->stub {path verdict})  override the byte-derived verdict per path
     (install! stub)          register as the ITxlogOps impl, returns stub
     with-stub-ops            clojure.test :each fixture (install + restore)
     (calls stub)             recorded [op & args] vectors, oldest first"
  (:require [clojure.java.io :as io]
            [hive-mcp.storage.recovery :as rec]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Byte-derived verdicts
;; =============================================================================

(defn- all-zero?
  [^bytes bs]
  (every? zero? bs))

(defn- verdict-from-bytes
  "One of :ok | :tail-zeroed | :unhealable for the file at PATH."
  [path]
  (let [f (io/file path)]
    (cond
      (not (.exists f))       :unhealable
      (zero? (.length f))     :ok
      :else (let [bs (byte-array (.length f))]
              (with-open [in (io/input-stream f)] (.read in bs))
              (if (all-zero? bs) :tail-zeroed :unhealable)))))

(defn- verdict
  [overrides path]
  (or (get overrides path)
      (get overrides (.getName (io/file path)))
      (verdict-from-bytes path)))

(defn- truncate-file!
  "Trim PATH to NEW-LEN bytes. Returns the size record."
  [path new-len]
  (let [f   (io/file path)
        old (.length f)]
    (with-open [raf (java.io.RandomAccessFile. f "rw")]
      (.setLength raf (long new-len)))
    {:old-size      old
     :new-size      new-len
     :dropped-bytes (- old new-len)
     :truncated?    true}))

;; =============================================================================
;; The stub
;; =============================================================================

(defrecord StubTxlogOps [overrides calls]
  rec/ITxlogOps

  (scan-segment [_this path opts]
    (swap! calls conj [:scan-segment path opts])
    (case (verdict @overrides path)
      :ok          {:partial-tail? false}
      :tail-zeroed (if (:allow-preallocated-tail? opts)
                     {:partial-tail? true :valid-end 0}
                     (throw (ex-info "strict scan rejected preallocated tail"
                                     {:path path :stub/verdict :tail-zeroed})))
      :unhealable  (throw (ex-info "segment unreadable"
                                   {:path path :stub/verdict :unhealable}))))

  (segment-files [_this dir]
    (swap! calls conj [:segment-files dir])
    (->> (.listFiles (io/file dir))
         (filter #(.isFile ^java.io.File %))
         (sort-by #(.getName ^java.io.File %))
         (mapv (fn [f] {:file f}))))

  (truncate-partial-tail! [_this path opts]
    (swap! calls conj [:truncate-partial-tail! path opts])
    (truncate-file! path 0)))

;; =============================================================================
;; Construction + registration
;; =============================================================================

(defn ->stub
  "A fresh StubTxlogOps. OVERRIDES maps an absolute path or bare filename to
   an explicit :ok | :tail-zeroed | :unhealable verdict."
  ([] (->stub {}))
  ([overrides] (->StubTxlogOps (atom (or overrides {})) (atom []))))

(defn calls
  "Recorded [op & args] vectors for STUB, oldest first."
  [stub]
  @(:calls stub))

(defn calls-of
  "Recorded arg vectors for OP only."
  [stub op]
  (into [] (comp (filter #(= op (first %))) (map #(vec (rest %)))) (calls stub)))

(defn install!
  "Register STUB as the ITxlogOps implementation. Returns STUB."
  [stub]
  (rec/set-txlog-ops! stub)
  stub)

(defn with-stub-ops
  "clojure.test fixture: install a fresh stub for the test, then restore the
   implementation that was registered before."
  [f]
  (let [prior (rec/current-txlog-ops)]
    (try
      (install! (->stub))
      (f)
      (finally (rec/set-txlog-ops! prior)))))
