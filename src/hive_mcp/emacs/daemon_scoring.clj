(ns hive-mcp.emacs.daemon-scoring
  "Delegation shim — routes to hive-emacs.daemon-scoring.

   Implementation extracted to the hive-emacs project. This shim aliases
   every public via runtime intern so existing hive-mcp callers keep
   working unchanged.

   Phase 2 will refactor consumers to use the extension registry;
   Phase 3 will delete this shim.

   Decision 20260429230453-7e7627cc."
  (:require [hive-emacs.daemon-scoring]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(doseq [[sym v] (ns-publics 'hive-emacs.daemon-scoring)]
  (intern *ns* (with-meta sym (meta v)) (deref v)))
