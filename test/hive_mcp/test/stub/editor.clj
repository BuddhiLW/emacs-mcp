(ns hive-mcp.test.stub.editor
  "IEditor stub — a scriptable editor backend for contract tests.

   `hive-mcp.protocols.editor` declares the contract; the concrete
   `EmacsclientEditor` lives in the hive-emacs sibling repo and implements it
   there. A hive-mcp test asserts the CONTRACT, so it drives a stub whose
   behaviour it scripts, never the sibling's adapter.

   API:
     (->stub-editor opts)   editor answering from OPTS
     with-editor            run f with an editor installed, restoring prior"
  (:require [hive-mcp.protocols.editor :as ed]
            [hive-dsl.result :as result]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord StubEditor [id available eval-fn feature-fn terminal-fn calls]
  ed/IEditor
  (editor-id [_] id)

  (available? [_]
    (swap! calls conj [:available?])
    (boolean available))

  (eval-expr [this code] (ed/eval-expr this code {}))
  (eval-expr [_ code opts]
    (swap! calls conj [:eval-expr code opts])
    (eval-fn code opts))

  (feature-available? [_ feature-name]
    (swap! calls conj [:feature-available? feature-name])
    (boolean (feature-fn feature-name)))

  (send-to-terminal [_ terminal-id text]
    (swap! calls conj [:send-to-terminal terminal-id text])
    (terminal-fn terminal-id text)))

(defn ->stub-editor
  "Editor answering from OPTS.

     :id           editor-id keyword                    (default :stub)
     :available    available? answer                    (default true)
     :eval-fn      (fn [code opts] Result)              (default ok \"nil\")
     :feature-fn   (fn [feature-name] boolean)          (default true)
     :terminal-fn  (fn [terminal-id text] Result)       (default ok true)

   Every call is recorded; read it with `calls`."
  [{:keys [id available eval-fn feature-fn terminal-fn]
    :or   {id          :stub
           available   true
           eval-fn     (fn [_ _] (result/ok "nil"))
           feature-fn  (constantly true)
           terminal-fn (fn [_ _] (result/ok true))}}]
  (->StubEditor id available eval-fn feature-fn terminal-fn (atom [])))

(defn calls
  "Recorded [op & args] vectors for STUB, oldest first."
  [stub]
  @(:calls stub))

(defn with-editor
  "Run F with EDITOR installed as the active editor, restoring the prior one."
  [editor f]
  (let [prior (when (ed/editor-set?) (ed/get-editor))]
    (try
      (ed/set-editor! editor)
      (f)
      (finally
        (ed/clear-editor!)
        (when prior (ed/set-editor! prior))))))
