(ns hive-mcp.test.stub.olympus
  "Stub `:emacs/olympus-*` layout calculators.

   `hive-mcp.emacs-ext.olympus` resolves each calculator from the extension
   registry and returns nil when hive-emacs is not loaded, so a cold JVM sees
   nil where the tool contract promises a map.

   Layout contract reproduced here:
     n <= per-tab  -> {:rows R :cols C}    single grid
     n >  per-tab  -> {:tabs T :per-tab P} tabbed

   API:
     with-olympus-layout   run f with the calculators registered
     olympus-fixture       clojure.test :each fixture"
  (:require [hive-mcp.test.stub.extensions :as ext-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private per-tab 4)

(defn- ceil-div [a b] (long (Math/ceil (/ (double a) (double b)))))

(defn calculate-layout
  "Grid for N lings: a single grid up to `per-tab`, tabbed beyond it."
  [n]
  (cond
    (or (nil? n) (zero? n)) {:rows 0 :cols 0}
    (<= n per-tab)          (let [cols (long (Math/ceil (Math/sqrt (double n))))]
                              {:rows (ceil-div n cols) :cols cols})
    :else                   {:tabs (ceil-div n per-tab) :per-tab per-tab}))

(defn assign-positions
  "Map each ling to {:row :col :tab}, filling row-major within each tab."
  [lings layout]
  (let [cols (or (:cols layout) (:per-tab layout) 1)
        cols (max 1 cols)
        cap  (or (:per-tab layout) Long/MAX_VALUE)]
    (into {}
          (map-indexed
           (fn [i ling]
             (let [id      (or (:slave/id ling) (:id ling) ling)
                   tab     (if (:tabs layout) (quot i cap) 0)
                   in-tab  (if (:tabs layout) (rem i cap) i)]
               [id {:row (quot in-tab cols) :col (rem in-tab cols) :tab tab}])))
          lings)))

(defn position-for-cell
  "The ling id occupying [ROW COL TAB], or nil."
  [positions row col tab]
  (some (fn [[id p]]
          (when (and (= row (:row p)) (= col (:col p)) (= tab (:tab p)))
            id))
        positions))

(def builders
  "The `:emacs/olympus-*` extension keys `hive-mcp.emacs-ext.olympus` resolves."
  {:emacs/olympus-calculate-layout  calculate-layout
   :emacs/olympus-assign-positions  assign-positions
   :emacs/olympus-position-for-cell position-for-cell})

(defn with-olympus-layout
  "Run F with the `:emacs/olympus-*` calculators registered."
  [f]
  (ext-stub/with-extensions builders f))

(defn olympus-fixture
  "clojure.test :each fixture form of `with-olympus-layout`."
  [f]
  (with-olympus-layout f))
