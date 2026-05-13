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
    (is (= :wal-corrupt (rec/classify-open-failure (ex "Invalid txlog header"))))))

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
