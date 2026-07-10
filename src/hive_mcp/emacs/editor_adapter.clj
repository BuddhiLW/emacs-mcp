(ns hive-mcp.emacs.editor-adapter
  "Delegation shim — routes to hive-emacs.editor-adapter.

   Implementation (including the EmacsclientEditor defrecord) extracted to
   the hive-emacs project. This shim aliases every public from
   hive-emacs.editor-adapter via runtime intern so existing hive-mcp
   callers keep working unchanged. defrecord factory fns
   (`->EmacsclientEditor`, `map->EmacsclientEditor`) are aliased through
   ns-publics; the underlying class lives in hive-emacs.

   Phase 2 will refactor consumers to use the extension registry;
   Phase 3 will delete this shim.

   Decision 20260429230453-7e7627cc."
  (:require [hive-emacs.editor-adapter]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(doseq [[sym v] (ns-publics 'hive-emacs.editor-adapter)]
  (intern *ns* (with-meta sym (meta v)) (deref v)))
