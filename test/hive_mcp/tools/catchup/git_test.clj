(ns hive-mcp.tools.catchup.git-test
  "Tests for git context gathering.

   Paradigms:
   1. Integration: real temp git repo for end-to-end validation
   2. Unit: mock-based tests via with-redefs for branch, status, log
   3. Shape: response contract — always {:branch string? :uncommitted boolean? :last-commit string?}"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [hive-mcp.tools.catchup.git :as git])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-temp-dir
  "Create a temporary directory that is deleted on JVM exit."
  ^File []
  (let [dir (Files/createTempDirectory "hive-git-test"
                                       (into-array FileAttribute []))]
    (.deleteOnExit (.toFile dir))
    (.toFile dir)))

(defn- response-shape?
  "True when m has exactly the expected keys with correct types."
  [m]
  (and (map? m)
       (string? (:branch m))
       (boolean? (:uncommitted m))
       (string? (:last-commit m))))

;; =============================================================================
;; Integration: Real Temp Git Repo
;; =============================================================================

(deftest gather-git-info-real-repo-test
  (testing "gather-git-info returns valid info from a real temporary git repo"
    (let [dir  (make-temp-dir)
          path (.getAbsolutePath dir)]
      ;; Initialise a git repo, configure user, and make one commit.
      (sh "git" "init" :dir path)
      (sh "git" "config" "user.email" "test@example.com" :dir path)
      (sh "git" "config" "user.name" "Test" :dir path)
      (spit (File. dir "README.md") "hello")
      (sh "git" "add" "." :dir path)
      (sh "git" "commit" "-m" "initial commit" :dir path)

      (let [info (git/gather-git-info path)]
        (is (response-shape? info)
            "Response must have correct shape")
        (is (contains? #{"main" "master"} (:branch info))
            "Branch should be main or master for a fresh repo")
        (is (false? (:uncommitted info))
            "No uncommitted changes after a clean commit")
        (is (re-find #"initial commit" (:last-commit info))
            "Last commit message should contain our commit text")))))

(deftest gather-git-info-real-repo-dirty-test
  (testing "uncommitted is true when working tree has changes"
    (let [dir  (make-temp-dir)
          path (.getAbsolutePath dir)]
      (sh "git" "init" :dir path)
      (sh "git" "config" "user.email" "test@example.com" :dir path)
      (sh "git" "config" "user.name" "Test" :dir path)
      (spit (File. dir "README.md") "hello")
      (sh "git" "add" "." :dir path)
      (sh "git" "commit" "-m" "first" :dir path)
      ;; Dirty the working tree.
      (spit (File. dir "new.txt") "dirty")

      (let [info (git/gather-git-info path)]
        (is (true? (:uncommitted info))
            "Uncommitted should be true with untracked file")))))

;; =============================================================================
;; Fallback: Non-Git Directory
;; =============================================================================

(deftest gather-git-info-non-git-directory-test
  (testing "returns fallback values for a directory that is not a git repo"
    (let [dir  (make-temp-dir)
          info (git/gather-git-info (.getAbsolutePath dir))]
      (is (response-shape? info)
          "Response shape must hold even for non-git dirs")
      (is (= "none" (:branch info)))
      (is (false? (:uncommitted info)))
      (is (= "none" (:last-commit info))))))

;; =============================================================================
;; Nil Directory
;; =============================================================================

(deftest gather-git-info-nil-directory-test
  (testing "does not throw when directory is nil"
    (let [info (git/gather-git-info nil)]
      (is (response-shape? info)
          "Response shape must hold for nil directory"))))

;; =============================================================================
;; Mock-Based: with-redefs on clojure.java.shell/sh
;; =============================================================================

(deftest gather-git-info-branch-detection-test
  (testing "correctly extracts branch name from rev-parse output"
    (with-redefs [sh (fn [& args]
                       (let [args-vec (vec args)]
                         (cond
                           (some #(= "rev-parse" %) args-vec)
                           {:exit 0 :out "feature/my-branch\n" :err ""}

                           (some #(= "--porcelain" %) args-vec)
                           {:exit 0 :out "" :err ""}

                           (some #(= "log" %) args-vec)
                           {:exit 0 :out "abc1234 - some msg\n" :err ""}

                           :else
                           {:exit 1 :out "" :err "unknown"})))]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (= "feature/my-branch" (:branch info)))))))

(deftest gather-git-info-uncommitted-true-test
  (testing "uncommitted is true when porcelain output is non-blank"
    (with-redefs [sh (fn [& args]
                       (let [args-vec (vec args)]
                         (cond
                           (some #(= "rev-parse" %) args-vec)
                           {:exit 0 :out "main\n" :err ""}

                           (some #(= "--porcelain" %) args-vec)
                           {:exit 0 :out " M src/core.clj\n?? tmp.txt\n" :err ""}

                           (some #(= "log" %) args-vec)
                           {:exit 0 :out "def5678 - fix bug\n" :err ""}

                           :else
                           {:exit 1 :out "" :err ""})))]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (true? (:uncommitted info)))))))

(deftest gather-git-info-uncommitted-false-test
  (testing "uncommitted is false when porcelain output is blank"
    (with-redefs [sh (fn [& args]
                       (let [args-vec (vec args)]
                         (cond
                           (some #(= "rev-parse" %) args-vec)
                           {:exit 0 :out "main\n" :err ""}

                           (some #(= "--porcelain" %) args-vec)
                           {:exit 0 :out "" :err ""}

                           (some #(= "log" %) args-vec)
                           {:exit 0 :out "aaa1111 - clean\n" :err ""}

                           :else
                           {:exit 1 :out "" :err ""})))]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (false? (:uncommitted info)))))))

(deftest gather-git-info-last-commit-format-test
  (testing "last-commit is trimmed from git log output"
    (with-redefs [sh (fn [& args]
                       (let [args-vec (vec args)]
                         (cond
                           (some #(= "rev-parse" %) args-vec)
                           {:exit 0 :out "main\n" :err ""}

                           (some #(= "--porcelain" %) args-vec)
                           {:exit 0 :out "" :err ""}

                           (some #(= "log" %) args-vec)
                           {:exit 0 :out "  cafe123 - refactor config  \n" :err ""}

                           :else
                           {:exit 1 :out "" :err ""})))]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (= "cafe123 - refactor config" (:last-commit info)))))))

(deftest gather-git-info-non-zero-exit-uses-fallback-test
  (testing "non-zero exit code returns fallback values per field"
    (with-redefs [sh (fn [& _args]
                       {:exit 128 :out "" :err "fatal: not a git repo"})]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (response-shape? info))
        (is (= "none" (:branch info)))
        (is (false? (:uncommitted info)))
        (is (= "none" (:last-commit info)))))))

(deftest gather-git-info-sh-throws-test
  (testing "exception in sh returns fallback without propagating"
    (with-redefs [sh (fn [& _args]
                       (throw (RuntimeException. "shell exploded")))]
      (let [info (git/gather-git-info "/fake/dir")]
        (is (response-shape? info)
            "Shape must hold even when sh throws")))))

;; =============================================================================
;; Response Shape Contract
;; =============================================================================

(deftest response-shape-always-holds-test
  (testing "response shape holds across varied inputs"
    (doseq [dir [nil "" "/tmp" "/nonexistent/path/xyz"]]
      (let [info (git/gather-git-info dir)]
        (is (response-shape? info)
            (str "Shape must hold for directory: " (pr-str dir)))))))
