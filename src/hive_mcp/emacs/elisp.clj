(ns hive-mcp.emacs.elisp
  "Alias layer over hive-spi.editor.elisp — pure elisp SOURCE construction.

   Nothing about building an elisp string needs an editor, so nothing here
   touches the addon or its lifecycle. Routing elisp EVALUATION is a separate
   question that belongs to hive-spi.editor.services; construction never did.

   Aliases rather than re-implements: 100+ call sites use `el/...` unchanged."
  (:require [hive-spi.editor.elisp]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(doseq [[sym v] (ns-publics 'hive-spi.editor.elisp)]
  (intern *ns* (with-meta sym (meta v)) (deref v)))
