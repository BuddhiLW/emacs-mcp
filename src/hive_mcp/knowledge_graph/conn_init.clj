(ns hive-mcp.knowledge-graph.conn-init
  "ISP-segregated lazy thread-safe conn-init (ENGINE-L1.2a root cause).

   The lazy-init pattern `(when (nil? @conn-atom)
                            (reset! conn-atom (open)))`
   is racy: under concurrency, two threads can both observe `nil` and
   both invoke `open`. For idempotent opens that's fine. For LMDB-
   backed Datalevin, the loser's `open` collides with the LMDB file
   lock and throws `Resource temporarily unavailable`. The slot
   breaker (ENGINE-L1.1) catches the resulting retry storm, but the
   structural race remains and produces the first failure on its own.

   This namespace owns the *single-init* capability as a small,
   focused protocol (ISP). `DatalevinStore` opts in because its
   underlying `dtlv/get-conn` is non-idempotent under concurrency.
   Other stores may opt in if their open exhibits the same shape.
   Backends with idempotent opens (e.g. DataScript in-memory)
   gain nothing from it and need not depend on this ns.

   ## Contract

     open-once!  — run `open-fn` at most once per IConnInit, even
                   under concurrent contention. Returns the cached
                   value on every subsequent call. `open-fn` runs
                   under a per-conn-init lock; concurrent callers
                   block on first init, then observe the cached
                   value lock-free.

     snapshot    — read-only view of the current cached value, or
                   nil when uninitialized. Side-effect-free.

     clear!      — reset to uninitialized; the next `open-once!`
                   will reopen. Caller is responsible for closing
                   the prior value (this protocol does not assume
                   any close shape).

   ## Default impl

   `atom-conn-init` is the standard implementation: an atom guarded
   by a JVM monitor. Tests can pass a pre-seeded atom; production
   uses the 0-arg constructor."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  )

(defprotocol IConnInit
  "Single-init capability. Implementations guarantee at-most-once
   evaluation of `open-fn` even under concurrent contention."
  (open-once! [this open-fn]
    "Run `open-fn` once; return its result. Subsequent calls return
     the cached value without re-invoking `open-fn`.")
  (snapshot [this]
    "Read-only view of the cached value, or nil when uninitialized.")
  (clear! [this]
    "Reset to uninitialized. Next `open-once!` will reopen.
     Caller is responsible for closing the previously cached value."))

(defn atom-conn-init
  "Default `IConnInit` implementation backed by an atom + JVM monitor.

   The atom is private to the returned reify; external code cannot
   bypass the double-checked locking. Pass a pre-seeded atom only in
   tests that need to inspect post-init state."
  ([] (atom-conn-init (atom nil)))
  ([state-atom]
   (reify IConnInit
     (open-once! [_ open-fn]
       (or @state-atom
           (locking state-atom
             (or @state-atom
                 (let [v (open-fn)]
                   (reset! state-atom v)
                   v)))))
     (snapshot [_] @state-atom)
     (clear! [_] (reset! state-atom nil)))))
