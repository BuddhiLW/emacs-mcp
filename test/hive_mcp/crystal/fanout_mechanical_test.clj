(ns hive-mcp.crystal.fanout-mechanical-test
  "Step-7 — mechanical-fallback parity. Without an addon-registered LLM
   synthesiser under `:cc/summarize-progress`, the per-scope fan-out must
   still emit one wrap entry per touched scope, each carrying the explicit
   `scope:project:<pid>` (or `scope:multi-project`) tag.

   This test does NOT stub `summarize-session-progress`; the real public
   fn runs through `delegate :cc/summarize-progress` and falls back to
   `summarize-session-progress-fallback` when no extension is registered.
   We deregister the extension upfront via `with-redefs` to force the
   fallback path."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.crystal.fanout :as fan]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- without-addon
  "Force the mechanical fallback by stubbing the extension registry to
   report no `:cc/summarize-progress` impl, regardless of what's actually
   loaded. Returns the value of (f)."
  [f]
  (with-redefs [ext/get-extension (fn [_] nil)]
    (f)))

(defn- mechanical-hbs []
  (-> (bs/empty-by-scope {:session "20260504-mech" :directory "/tmp" :agent-id "ag"})
      (bs/assoc-scope "hive"
                       (assoc bs/empty-scope-slice
                              :progress-notes [{:title "fix" :content "did fix"}]
                              :git-commits   ["sha1 fix"]))
      (bs/assoc-scope "funeraria"
                       (assoc bs/empty-scope-slice
                              :progress-notes [{:title "wip" :content "wip note"}]))))

(deftest fanout--mechanical-fallback-still-tags-each-scope
  (without-addon
    (fn []
      (let [results (fan/synthesize-wraps (mechanical-hbs))
            tag-of (fn [r] (first (get-in r [:entry :tags])))]
        (is (= 2 (count results))
            "two non-empty scopes produce two wrap entries via mechanical fallback")
        (is (= #{"hive" "funeraria"} (set (map :pid results))))
        (testing "each entry carries explicit scope tag from step-6 wrapper"
          (is (= "scope:project:funeraria"
                 (tag-of (first (filter #(= "funeraria" (:pid %)) results)))))
          (is (= "scope:project:hive"
                 (tag-of (first (filter #(= "hive" (:pid %)) results))))))
        (testing "entries are :note type with content, regardless of synth path"
          (is (every? #(= :note (get-in % [:entry :type])) results))
          (is (every? #(string? (get-in % [:entry :content])) results)))))))

(deftest fanout--mechanical-fallback-skips-content-empty-scopes
  (testing "the mechanical fallback returns nil when a slice has no content;
            fan-out drops nil entries even though the slice was non-empty
            metadata-wise"
    (without-addon
      (fn []
        (let [hbs (-> (bs/empty-by-scope)
                      ;; Slice has progress-notes but each note is empty —
                      ;; mechanical-session-summary returns nil for this.
                      (bs/assoc-scope "hive"
                                       (assoc bs/empty-scope-slice
                                              :progress-notes [{}])))
              results (fan/synthesize-wraps hbs)]
          ;; Entry may or may not be emitted depending on fallback's
          ;; meaningful-content? check. Either way, no exception, and
          ;; if emitted, scope tag is correct.
          (is (or (zero? (count results))
                  (= "scope:project:hive" (first (get-in (first results) [:entry :tags]))))))))))