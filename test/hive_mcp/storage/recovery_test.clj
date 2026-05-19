(ns hive-mcp.storage.recovery-test
  "Tests for ENGINE-L1.2 storage recovery — classification, quarantine,
   heal-and-open! policy dispatch."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.storage.recovery :as rec]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- ex
  "Build an exception with optional cause chain."
  ([msg] (Exception. ^String msg))
  ([msg cause] (Exception. ^String msg ^Throwable cause)))

(defn- tmp-db-path []
  (let [base (System/getProperty "java.io.tmpdir")
        rnd  (str "hive-recovery-test-" (random-uuid))]
    (.getAbsolutePath (io/file base rnd))))

;; -----------------------------------------------------------------------------
;; classify-open-failure
;; -----------------------------------------------------------------------------

(deftest test-classifies-wal-corruption-signatures
  (testing "known LMDB / WAL corruption substrings classify as :wal-corrupt"
    (is (= :wal-corrupt (rec/classify-open-failure (ex "MDB_CORRUPTED"))))
    (is (= :wal-corrupt (rec/classify-open-failure (ex "Page not found in MDB_PAGE_NOTFOUND"))))
    (is (= :wal-corrupt (rec/classify-open-failure (ex "checksum mismatch on segment"))))
    (is (= :wal-corrupt (rec/classify-open-failure (ex "Invalid txlog header"))))
    ;; Regression: 2026-05-19 :carto incident — datalevin error wording
    ;; is `txn-log` (hyphen), not `txlog`. Earlier sig list missed both
    ;; the inner "Invalid txn-log record magic" and outer "Txn-log segment
    ;; corruption" causes, falling through to :unknown.
    (is (= :wal-corrupt (rec/classify-open-failure (ex "Invalid txn-log record magic"))))
    (is (= :wal-corrupt (rec/classify-open-failure (ex "Txn-log segment corruption"))))
    (is (= :wal-corrupt (rec/classify-open-failure (ex "txn-log corruption at offset 42348"))))))

(deftest test-classifies-lock-contention
  (testing "lock-related EAGAIN signatures classify as :lock-contention"
    (is (= :lock-contention
           (rec/classify-open-failure (ex "Resource temporarily unavailable"))))
    (is (= :lock-contention (rec/classify-open-failure (ex "MDB_BUSY"))))))

(deftest test-classifies-version-mismatch
  (testing "version-mismatch signatures classify as :version-mismatch"
    (is (= :version-mismatch (rec/classify-open-failure (ex "MDB_VERSION_MISMATCH"))))
    (is (= :version-mismatch (rec/classify-open-failure (ex "schema version 3 != 4"))))))

(deftest test-classifies-unknown
  (testing "unrecognised errors classify as :unknown"
    (is (= :unknown (rec/classify-open-failure (ex "totally unrelated"))))
    (is (= :unknown (rec/classify-open-failure (Exception. (str nil)))))))

(deftest test-walks-cause-chain
  (testing "classification walks .getCause until a signature matches"
    (let [root (ex "MDB_CORRUPTED")
          mid  (ex "wrapped" root)
          outer (ex "top-level wrap" mid)]
      (is (= :wal-corrupt (rec/classify-open-failure outer))))))

;; -----------------------------------------------------------------------------
;; quarantine
;; -----------------------------------------------------------------------------

(deftest test-quarantine-path-is-deterministic-given-ts
  (testing "quarantine-path is pure when ts is supplied"
    (is (= "/var/lib/foo.corrupt.123456789"
           (rec/quarantine-path "/var/lib/foo" 123456789)))))

(deftest test-quarantine-renames-existing-dir
  (testing "quarantine! moves an existing directory to the target path"
    (let [src (tmp-db-path)
          src-file (io/file src)]
      (.mkdirs src-file)
      (spit (io/file src-file "data.txt") "hello")
      (let [target (rec/quarantine-path src 999)
            moved  (rec/quarantine! src target)]
        (try
          (is (= target moved))
          (is (not (.exists src-file)) "src must be gone after rename")
          (is (.exists (io/file target)) "target must exist")
          (is (= "hello" (slurp (io/file target "data.txt"))))
          (finally
            (let [t (io/file target)]
              (when (.exists t)
                (run! io/delete-file (reverse (file-seq t)))))))))))

(deftest test-quarantine-is-noop-when-src-missing
  (testing "quarantine! returns nil and does not throw when src doesn't exist"
    (is (nil? (rec/quarantine! (tmp-db-path))))))

;; -----------------------------------------------------------------------------
;; heal-and-open! policy dispatch
;; -----------------------------------------------------------------------------

(deftest test-heal-and-open-happy-path
  (testing "open-fn succeeds first try → returns its value, no retries"
    (let [calls (atom 0)
          conn  (rec/heal-and-open!
                 {:policy {:strategy :throw}
                  :db-path "/dev/null"}
                 (fn [] (swap! calls inc) :conn))]
      (is (= :conn conn))
      (is (= 1 @calls)))))

(deftest test-throw-policy-rethrows-on-failure
  (testing ":throw policy rethrows without retrying"
    (let [calls (atom 0)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Datalevin open failed"
           (rec/heal-and-open!
            {:policy {:strategy :throw}
             :db-path "/dev/null"}
            (fn [] (swap! calls inc) (throw (ex "MDB_CORRUPTED"))))))
      (is (= 1 @calls) "throw policy must not retry"))))

(deftest test-audit-policy-rethrows-without-mutating
  (testing ":audit policy classifies + rethrows without quarantining"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (spit (io/file src "data.txt") "still here")
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (rec/heal-and-open!
                      {:policy {:strategy :audit}
                       :db-path src}
                      (fn [] (throw (ex "MDB_CORRUPTED"))))))
        (is (.exists (io/file src "data.txt"))
            "audit must never touch the on-disk store")
        (finally
          (run! io/delete-file (reverse (file-seq (io/file src)))))))))

(deftest test-quarantine-policy-recovers-on-wal-corrupt
  (testing ":quarantine + :wal-corrupt → moves db aside + retries"
    (let [src     (tmp-db-path)
          calls   (atom 0)
          actions (atom [])]
      (.mkdirs (io/file src))
      (spit (io/file src "data.txt") "corrupt data")
      (try
        (let [conn (rec/heal-and-open!
                    {:policy {:strategy :quarantine :max-attempts 2}
                     :db-path src}
                    (fn []
                      (swap! calls inc)
                      (if (= 1 @calls)
                        (do (swap! actions conj :throw)
                            (throw (ex "MDB_CORRUPTED")))
                        (do (swap! actions conj :ok)
                            :healthy-conn))))]
          (is (= :healthy-conn conn))
          (is (= 2 @calls) "expect two attempts: corrupt + recovered")
          (is (= [:throw :ok] @actions))
          (is (not (.exists (io/file src))) "src moved aside"))
        (finally
          (doseq [f (.listFiles (.getParentFile (io/file src)))
                  :when (.startsWith (.getName ^java.io.File f)
                                     (.getName (io/file src)))]
            (run! io/delete-file (reverse (file-seq f)))))))))

(deftest test-quarantine-policy-skips-non-corrupt-classifications
  (testing ":quarantine policy does not quarantine on :lock-contention etc."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"policy aborted"
         (rec/heal-and-open!
          {:policy {:strategy :quarantine}
           :db-path "/dev/null"}
          (fn [] (throw (ex "Resource temporarily unavailable"))))))))

(deftest test-heal-and-open-exhausts-attempts
  (testing "if recovery keeps failing past max-attempts, throws :storage/heal-exhausted"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (try
        (let [thrown (try
                       (rec/heal-and-open!
                        {:policy {:strategy :quarantine :max-attempts 2}
                         :db-path src}
                        (fn [] (throw (ex "MDB_CORRUPTED"))))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown))
          (is (contains? #{:storage/heal-exhausted
                           :storage/quarantine-failed}
                         (:err (ex-data thrown)))))
        (finally
          (doseq [f (.listFiles (.getParentFile (io/file src)))
                  :when (and (.startsWith (.getName ^java.io.File f)
                                          (.getName (io/file src))))]
            (run! io/delete-file (reverse (file-seq f)))))))))

;; -----------------------------------------------------------------------------
;; :truncate strategy + txn-log heal helpers
;; -----------------------------------------------------------------------------

(defn- write-bytes!
  "Write a byte-array to the named segment file under `<db>/txlog/`,
   creating the directory if needed."
  [^String db-path seg-name byte-array-data]
  (let [dir (io/file db-path "txlog")
        f   (io/file dir seg-name)]
    (.mkdirs dir)
    (.createNewFile f)
    (with-open [out (java.io.FileOutputStream. f)]
      (.write out ^bytes byte-array-data))
    f))

(defn- garbage-bytes
  "Deterministic non-zero byte payload — every scan path rejects it."
  [n]
  (let [ba (byte-array n)]
    (dotimes [i n]
      (aset-byte ba i (byte (- (mod (* i 31) 200) 100))))
    ba))

(defn- cleanup-db-and-siblings!
  "Wipe `db-path` plus any `<db>.txlog-heal.*` or `<db>.corrupt.*`
   sibling dirs left by the recovery strategies."
  [db-path]
  (doseq [f (.listFiles (.getParentFile (io/file db-path)))
          :when (.startsWith (.getName ^java.io.File f)
                             (.getName (io/file db-path)))]
    (run! io/delete-file (reverse (file-seq f)))))

(deftest test-truncate-tail-noop-when-no-txlog-dir
  (testing "truncate-tail! returns empty when <db>/txlog/ does not exist"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (try
        (is (= [] (rec/truncate-tail! src)))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-tail-noop-when-segment-is-clean
  (testing "truncate-tail! skips segments whose strict scan succeeds"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (try
        ;; 0-byte segment passes strict scan as :ok
        (write-bytes! src "segment-0000000000000001.wal" (byte-array 0))
        (is (= [] (rec/truncate-tail! src)))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-tail-heals-tail-zeroed-segment
  (testing "truncate-tail! truncates a tail-zeroed segment + leaves forensic copy"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (try
        ;; 4KB of pure zeros — strict scan rejects, lenient scan reports
        ;; {:partial-tail? true :valid-end 0}, truncate trims to 0 bytes.
        (let [seg (write-bytes! src "segment-0000000000000002.wal"
                                (byte-array 4096))
              report (rec/truncate-tail! src 12345)
              entry  (first report)]
          (is (= 1 (count report)))
          (is (:truncated? entry))
          (is (= :tail-zeroed (:sub-classification entry)))
          (is (= 4096 (:old-size entry)))
          (is (= 0 (:new-size entry)))
          (is (= 4096 (:dropped-bytes entry)))
          ;; Segment file truncated
          (is (= 0 (.length seg)))
          ;; Forensic copy lives at <db>.txlog-heal.<ts>/<seg>.before-truncate
          (let [backup (io/file (:backup entry))]
            (is (.exists backup))
            (is (= 4096 (.length backup)))
            (is (.endsWith (.getName backup) ".before-truncate"))))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-tail-leaves-mid-segment-corruption-alone
  (testing "truncate-tail! marks mid-segment corruption :unhealable, does not mutate"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (try
        (let [seg (write-bytes! src "segment-0000000000000003.wal"
                                (garbage-bytes 256))
              original-len (.length seg)
              report (rec/truncate-tail! src 12346)
              entry  (first report)]
          (is (= 1 (count report)))
          (is (not (:truncated? entry)))
          (is (= :mid-segment (:sub-classification entry)))
          ;; Segment untouched
          (is (= original-len (.length seg)))
          ;; No forensic copy is created when nothing was truncated
          (is (nil? (:backup entry))))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-strategy-end-to-end
  (testing ":truncate strategy via heal-and-open! truncates + retries open"
    (let [src   (tmp-db-path)
          calls (atom 0)]
      (.mkdirs (io/file src))
      (write-bytes! src "segment-0000000000000004.wal" (byte-array 2048))
      (try
        (let [conn (rec/heal-and-open!
                    {:policy {:strategy :truncate :max-attempts 2}
                     :db-path src}
                    (fn []
                      (swap! calls inc)
                      (if (= 1 @calls)
                        (throw (ex "Invalid txn-log record magic"))
                        :healthy-conn)))]
          (is (= :healthy-conn conn))
          (is (= 2 @calls)
              "expected one throw + one retry after segment heal"))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-strategy-aborts-on-unhealable-corruption
  (testing ":truncate strategy aborts when no segment has a recoverable tail"
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (write-bytes! src "segment-0000000000000005.wal" (garbage-bytes 256))
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"policy aborted"
             (rec/heal-and-open!
              {:policy {:strategy :truncate}
               :db-path src}
              (fn [] (throw (ex "Invalid txn-log record magic"))))))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-truncate-strategy-skips-non-wal-classifications
  (testing ":truncate strategy does not touch the store on :lock-contention etc."
    (let [src (tmp-db-path)]
      (.mkdirs (io/file src))
      (write-bytes! src "segment-0000000000000006.wal" (byte-array 1024))
      (let [seg (io/file src "txlog" "segment-0000000000000006.wal")
            len-before (.length seg)]
        (try
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"policy aborted"
               (rec/heal-and-open!
                {:policy {:strategy :truncate}
                 :db-path src}
                (fn [] (throw (ex "Resource temporarily unavailable"))))))
          (is (= len-before (.length seg))
              ":truncate must never mutate when classification is not :wal-corrupt")
          (finally (cleanup-db-and-siblings! src)))))))

(deftest test-strategy-chain-truncate-then-quarantine
  (testing "vector strategy walks left-to-right and stops at first :retry"
    (let [src   (tmp-db-path)
          calls (atom 0)]
      (.mkdirs (io/file src))
      ;; Tail-zeroed segment → :truncate heals on first try
      (write-bytes! src "segment-0000000000000007.wal" (byte-array 2048))
      (try
        (let [conn (rec/heal-and-open!
                    {:policy {:strategy [:truncate :quarantine :throw]
                              :max-attempts 2}
                     :db-path src}
                    (fn []
                      (swap! calls inc)
                      (if (= 1 @calls)
                        (throw (ex "Invalid txn-log record magic"))
                        :healthy-conn)))]
          (is (= :healthy-conn conn))
          ;; Source dir should still exist — :truncate succeeded so
          ;; :quarantine never fired.
          (is (.exists (io/file src))
              "chain must short-circuit at :truncate, never quarantine")
          (is (= 2 @calls)))
        (finally (cleanup-db-and-siblings! src))))))

(deftest test-strategy-chain-falls-through-to-quarantine-on-unhealable
  (testing "vector strategy falls through to :quarantine when :truncate aborts"
    (let [src   (tmp-db-path)
          calls (atom 0)]
      (.mkdirs (io/file src))
      ;; Mid-segment garbage → :truncate cannot heal, :quarantine moves aside
      (write-bytes! src "segment-0000000000000008.wal" (garbage-bytes 256))
      (try
        (let [conn (rec/heal-and-open!
                    {:policy {:strategy [:truncate :quarantine]
                              :max-attempts 2}
                     :db-path src}
                    (fn []
                      (swap! calls inc)
                      (if (= 1 @calls)
                        (throw (ex "Invalid txn-log record magic"))
                        :healthy-conn)))]
          (is (= :healthy-conn conn))
          (is (not (.exists (io/file src)))
              ":quarantine ran after :truncate aborted — src moved aside"))
        (finally (cleanup-db-and-siblings! src))))))
