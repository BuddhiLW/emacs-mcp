(ns hive-mcp.tools.magit-files-test
  "`files` as a path LIST, and the refusal that keeps a commit off an index the
   operation did not stage.

   Regression: a batch-commit operation whose `files` was a space-separated
   string staged nothing and then committed whatever the index already held
   under that operation's message."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.test.stub.emacs-ext :as se]
            [hive-mcp.tools.consolidated.git :as git]
            [hive-mcp.tools.consolidated.magit :as cmagit]
            [hive-mcp.tools.core :as core]
            [hive-mcp.tools.magit :as magit]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private dir "/tmp/repo")

(defn- staged-responses
  "Script the stub: the stage-and-verify round trip answers VERDICT, the commit
   round trip answers like a real commit."
  [verdict]
  [["hive-mcp-magit-api-commit" (se/elisp-str "1 file changed")]
   ["git diff --cached" (se/elisp-str verdict)]])

(defn- committed?
  [stub]
  (boolean (some #(str/includes? % "hive-mcp-magit-api-commit") (se/evaluated stub))))

(defn- stage-file-args
  "The FILES argument each api-stage call received."
  [stub]
  (mapv #(nth % 2) (se/calls-of stub :emacs/require-and-call)))

(defn- stage-elisp
  [stub]
  (first (filter #(str/includes? % "git diff --cached") (se/evaluated stub))))

(defn- batch-op-results
  [r]
  (:results (json/read-str (:text r) :key-fn keyword)))

;;; ===========================================================================
;;; The reader
;;; ===========================================================================

(deftest normalize-files-takes-a-list-or-a-string
  (testing "a list arrives as a vector of paths"
    (is (= ["a.clj" "b.clj"] (magit/normalize-files ["a.clj" "b.clj"])))
    (is (= ["a.clj"] (magit/normalize-files (list "a.clj")))))

  (testing "a string of several paths is split on whitespace"
    (is (= ["a.clj" "b.clj"] (magit/normalize-files "a.clj b.clj")))
    (is (= ["a.clj" "b.clj" "c.clj"] (magit/normalize-files "a.clj\n b.clj\tc.clj")))
    (is (= ["src/a.clj"] (magit/normalize-files "src/a.clj"))
        "one path is still one path"))

  (testing "'all' keeps its meaning"
    (is (= :all (magit/normalize-files "all")))
    (is (= :all (magit/normalize-files ["all"])))
    (is (= :all (magit/normalize-files 'all))))

  (testing "nothing usable reads as nil, never as an empty stage"
    (is (nil? (magit/normalize-files nil)))
    (is (nil? (magit/normalize-files "   ")))
    (is (nil? (magit/normalize-files [])))))

;;; ===========================================================================
;;; stage
;;; ===========================================================================

(deftest stage-accepts-an-array-of-paths
  (se/with-stub-emacs [stub {}]
    (magit/handle-magit-stage {:files ["src/a.clj" "src/b.clj"] :directory dir})
    (is (= [["src/a.clj" "src/b.clj"]] (stage-file-args stub)))))

(deftest stage-splits-a-whitespace-separated-string
  (se/with-stub-emacs [stub {}]
    (magit/handle-magit-stage {:files "src/a.clj src/b.clj" :directory dir})
    (is (= [["src/a.clj" "src/b.clj"]] (stage-file-args stub))
        "the path-list string that used to reach Emacs as ONE nonexistent path")))

(deftest stage-all-still-means-all
  (se/with-stub-emacs [stub {}]
    (magit/handle-magit-stage {:files "all" :directory dir})
    (is (= ['all] (stage-file-args stub)))))

(deftest stage-without-files-is-an-error-not-a-silent-no-op
  (se/with-stub-emacs [stub {}]
    (let [r (magit/handle-magit-stage {:directory dir})]
      (is (true? (:isError r)))
      (is (empty? (se/evaluated stub)) "nothing reached Emacs"))))

;;; ===========================================================================
;;; commit — stage the operation's own paths, or refuse
;;; ===========================================================================

(deftest commit-stages-its-own-paths-then-commits
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:staged-ok")}]
    (let [r (magit/handle-magit-commit {:message "feat: x"
                                        :files "src/a.clj src/b.clj"
                                        :directory dir})]
      (is (nil? (:isError r)))
      (is (some? (stage-elisp stub)) "the paths are staged before the commit")
      (is (str/includes? (stage-elisp stub) "(\"src/a.clj\" \"src/b.clj\")")
          "both paths reach the stage call")
      (is (committed? stub)))))

(deftest commit-refuses-a-path-that-does-not-exist
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:missing-path:src/gone.clj")}]
    (let [r (magit/handle-magit-commit {:message "feat: x"
                                        :files ["src/gone.clj"]
                                        :directory dir})]
      (is (true? (:isError r)))
      (is (str/includes? (:text r) ":magit/path-not-found") "a typed error")
      (is (str/includes? (:text r) "src/gone.clj") "naming the path")
      (is (not (committed? stub)) "a refused stage must never reach the commit"))))

(deftest commit-refuses-when-nothing-staged-for-the-listed-paths
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:nothing-staged")}]
    (let [r (magit/handle-magit-commit {:message "feat(html): x"
                                        :files "src/a.clj src/b.clj"
                                        :directory dir})]
      (is (true? (:isError r)))
      (is (str/includes? (:text r) ":magit/nothing-staged"))
      (is (not (committed? stub))
          "the measured defect: a pre-staged deletion was committed under this message"))))

(deftest commit-refuses-a-stage-answer-it-cannot-read
  (testing "an unrecognized verdict is a refusal, not a fall-through"
    (se/with-stub-emacs [stub {}]
      (let [r (magit/handle-magit-commit {:message "m" :files ["src/a.clj"] :directory dir})]
        (is (true? (:isError r)))
        (is (not (committed? stub)))))))

(deftest commit-without-files-is-untouched
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:staged-ok")}]
    (let [r (magit/handle-magit-commit {:message "m" :directory dir})]
      (is (nil? (:isError r)))
      (is (nil? (stage-elisp stub)) "no staging round trip when no paths were named")
      (is (committed? stub)))))

;;; ===========================================================================
;;; batch-commit
;;; ===========================================================================

(deftest batch-commit-stages-a-path-list-per-operation
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:staged-ok")}]
    (let [handler (:batch-commit git/handlers)
          r (handler {:directory dir
                      :operations [{:message "feat: one" :files ["src/a.clj" "src/b.clj"]}
                                   {:message "fix: two" :files "src/c.clj"}]})
          results (batch-op-results r)]
      (is (= 2 (count results)))
      (is (every? #(nil? (get-in % [:result :isError])) results))
      (is (= 2 (count (filter #(str/includes? % "hive-mcp-magit-api-commit")
                              (se/evaluated stub))))))))

(deftest batch-commit-refuses-an-operation-that-stages-nothing
  (se/with-stub-emacs [stub {:responses (staged-responses "hive-mcp:nothing-staged")}]
    (let [handler (:batch-commit git/handlers)
          r (handler {:directory dir
                      :operations [{:message "feat(html): projections"
                                    :files "src/plato/html.cljc src/plato/reveal.cljs"}]})
          [op] (batch-op-results r)]
      (is (true? (get-in op [:result :isError])))
      (is (str/includes? (get-in op [:result :text]) ":magit/nothing-staged"))
      (is (not (committed? stub))))))

;;; ===========================================================================
;;; Schema — one definition, N tools
;;; ===========================================================================

(deftest both-git-tools-declare-files-from-one-definition
  (doseq [[label td] [["git" git/tool-def] ["magit" cmagit/tool-def]]]
    (let [p (get-in td [:inputSchema :properties "files"])]
      (is (= (get core/git-files-property "files") p)
          (str label " restates the property instead of splicing the one definition"))
      (is (= ["string" "array"] (:type p))
          (str label " does not advertise a list"))
      (is (re-find #"(?i)list" (:description p))
          (str label " description does not say list-or-string")))
    (is (= (get core/git-files-property "files")
           (get-in td [:inputSchema :properties "operations" :items :properties "files"]))
        (str label " batch operation files drifted from the shared definition"))))
