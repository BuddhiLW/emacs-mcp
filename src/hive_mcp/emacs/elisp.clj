(ns hive-mcp.emacs.elisp
  "Alias layer over hive-elisp — pure elisp SOURCE construction.

   This namespace used to `:require [hive-emacs.elisp]` and intern its
   publics: a compile-time dependency from the HOST onto an ADDON, and the
   single largest reason hive-mcp could not drop hive-emacs from :deps.

   It now aliases hive-elisp, a pure leaf. Nothing about building an elisp
   string needs an editor, so nothing here touches the addon or its
   lifecycle. Routing elisp EVALUATION is a separate question that belongs to
   hive-spi.editor.services; construction never did.

   Aliases rather than re-implements: 100+ call sites use `el/...` unchanged."
  (:require [hive-elisp.core]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(doseq [[sym v] (ns-publics 'hive-elisp.core)]
  (intern *ns* (with-meta sym (meta v)) (deref v)))
