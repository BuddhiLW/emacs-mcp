(ns hive-mcp.test.stub.emacs-ext
  "Stub `:emacs/*` extension seam for driver-free tests.

   Every elisp round-trip in hive-mcp core goes through a façade in
   hive-mcp.emacs-ext.* that resolves a callable from
   hive-mcp.extensions.registry. hive-emacs contributes those callables from
   its IAddon (hooks); on a cold run the registry is empty and each façade
   returns a uniformly-shaped miss — {:success false :error \"hive-emacs not
   loaded — extension :emacs/eval-elisp unavailable\"} from the client façade,
   plain nil from the elisp/notify/daemon-store façades.

   The registry is the seam. This ns registers a scriptable in-process Emacs
   in it rather than letting a test redefine a façade var or assert the miss.

   API:
     (->emacs)              a fresh stub, see options below
     (install! stub)        register every :emacs/* key, returns the stub
     with-emacs             clojure.test :each fixture (snapshot + restore)
     with-stub-emacs        macro binding a configured stub for a body
     (calls stub)           recorded [key & args] vectors, oldest first
     (calls-of stub k)      recorded arg vectors for one key
     (evaluated stub)       the elisp source strings passed to eval-elisp

   Contracts mirrored from hive-emacs:
     eval-elisp        => {:success true :result <elisp-printed string>}
                          {:success false :error <string>} on a scripted fault
     running?          => boolean
     buffer-list       => vector of buffer-name strings
     notify!           => true
     format-elisp      => the template with %s slots filled
     require-and-call* => an elisp source string
     daemon-store-*    => daemon id strings / nil"
  (:require [clojure.string :as str]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ext-keys
  "Every :emacs/* key the hive-mcp façades resolve."
  [:emacs/eval-elisp :emacs/eval-elisp-with-timeout :emacs/running?
   :emacs/buffer-list :emacs/current-buffer :emacs/current-file
   :emacs/switch-to-buffer :emacs/find-file :emacs/save-buffer
   :emacs/goto-line :emacs/insert-text :emacs/project-root :emacs/recent-files
   :emacs/notify!
   :emacs/format-elisp :emacs/require-and-call :emacs/require-and-call-json
   :emacs/require-and-call-text :emacs/require-and-call-plist-json
   :emacs/daemon-store-ensure-default! :emacs/daemon-store-select-for-ling
   :emacs/daemon-store-bind-ling! :emacs/daemon-store-unbind-ling!
   :emacs/daemon-store-get-for-ling :emacs/daemon-store-default-id])

(defn elisp-str
  "S as elisp prints it back: a quoted, escaped string literal.

   eval-elisp answers with the PRINTED form of the elisp value, so a handler
   expecting a string sees the quotes."
  [s]
  (str "\"" (str/escape (str s) {\\ "\\\\" \" "\\\""}) "\""))

(defn- record!
  [state k args]
  (swap! state update :calls conj (into [k] args)))

(defn- ->result
  "Normalize a scripted eval-elisp answer to the hive-emacs envelope.
   A callable answer is applied to the elisp source first."
  [answer code]
  (let [answer (if (and (ifn? answer) (not (map? answer)) (not (string? answer)))
                 (answer code)
                 answer)]
    (cond
      (nil? answer)                     {:success true :result "nil"}
      (and (map? answer)
           (contains? answer :success)) answer
      :else                             {:success true :result (str answer)})))

(defn- eval-answer
  "The scripted answer for CODE: the first :responses entry whose matcher
   claims it, else :default-response.

   A matcher is either a string (substring of the elisp source) or a regex.
   An answer may be a value, an envelope map, or a fn of the elisp source."
  [{:keys [responses default-response]} code]
  (let [hit (some (fn [[matcher answer]]
                    (when (if (instance? java.util.regex.Pattern matcher)
                            (re-find matcher code)
                            (str/includes? code (str matcher)))
                      answer))
                  responses)]
    (->result (if (some? hit) hit default-response) code)))

(defrecord StubEmacs [state])

(defn ->emacs
  "A recording, scriptable stub Emacs.

   OPTS:
     :responses         ordered seq of [matcher answer] pairs consulted by
                        eval-elisp. MATCHER is a substring or a regex tested
                        against the elisp source; ANSWER is a full envelope
                        map, or a value wrapped as {:success true :result ...}.
                        Use `elisp-str` for an answer the handler reads back
                        as an elisp string.
     :default-response  answer for elisp no matcher claims (default nil, i.e.
                        {:success true :result \"nil\"}).
     :running?          what emacs-running? reports (default true).
     :buffers           buffer-name strings (default [\"*scratch*\"]).
     :current-buffer    default \"*scratch*\".
     :current-file      default nil.
     :project-root      default nil.
     :recent-files      default [].
     :default-daemon-id default \"daemon-0\"."
  ([] (->emacs {}))
  ([opts]
   (->StubEmacs (atom (merge {:responses         []
                              :default-response  nil
                              :running?          true
                              :buffers           ["*scratch*"]
                              :current-buffer    "*scratch*"
                              :current-file      nil
                              :project-root      nil
                              :recent-files      []
                              :default-daemon-id "daemon-0"
                              :bindings          {}
                              :calls             []}
                             opts)))))

(defn calls
  "Recorded [key & args] vectors, oldest first."
  [stub]
  (:calls @(:state stub)))

(defn calls-of
  "Recorded arg vectors for one :emacs/* key."
  [stub k]
  (into [] (comp (filter #(= k (first %))) (map #(vec (rest %)))) (calls stub)))

(defn evaluated
  "The elisp source strings passed to eval-elisp, oldest first."
  [stub]
  (mapv first (calls-of stub :emacs/eval-elisp)))

(defn- handlers
  "{ext-key callable} for STUB."
  [{:keys [state]}]
  {:emacs/eval-elisp
   (fn [code]
     (record! state :emacs/eval-elisp [code])
     (eval-answer @state code))

   :emacs/eval-elisp-with-timeout
   (fn [code timeout-ms]
     (record! state :emacs/eval-elisp-with-timeout [code timeout-ms])
     (eval-answer @state code))

   :emacs/running?       (fn [] (record! state :emacs/running? [])
                           (boolean (:running? @state)))
   :emacs/buffer-list    (fn [] (record! state :emacs/buffer-list [])
                           (vec (:buffers @state)))
   :emacs/current-buffer (fn [] (record! state :emacs/current-buffer [])
                           (:current-buffer @state))
   :emacs/current-file   (fn [] (record! state :emacs/current-file [])
                           (:current-file @state))
   :emacs/project-root   (fn [] (record! state :emacs/project-root [])
                           (:project-root @state))
   :emacs/recent-files   (fn [] (record! state :emacs/recent-files [])
                           (vec (:recent-files @state)))
   :emacs/save-buffer    (fn [] (record! state :emacs/save-buffer []) true)

   :emacs/switch-to-buffer (fn [buffer-name]
                             (record! state :emacs/switch-to-buffer [buffer-name])
                             (swap! state assoc :current-buffer buffer-name)
                             true)
   :emacs/find-file        (fn [file-path]
                             (record! state :emacs/find-file [file-path])
                             (swap! state assoc :current-file file-path)
                             true)
   :emacs/goto-line        (fn [n] (record! state :emacs/goto-line [n]) true)
   :emacs/insert-text      (fn [text] (record! state :emacs/insert-text [text]) true)
   :emacs/notify!          (fn [m] (record! state :emacs/notify! [m]) true)

   :emacs/format-elisp
   (fn [template & args]
     (record! state :emacs/format-elisp (into [template] args))
     (apply format template args))

   :emacs/require-and-call
   (fn [feature fn-sym & args]
     (record! state :emacs/require-and-call (into [feature fn-sym] args))
     (str "(progn (require '" feature ") (" fn-sym
          (when (seq args) (str " " (str/join " " args))) "))"))

   :emacs/require-and-call-json
   (fn [feature fn-sym & args]
     (record! state :emacs/require-and-call-json (into [feature fn-sym] args))
     (str "(json-encode (progn (require '" feature ") (" fn-sym
          (when (seq args) (str " " (str/join " " args))) ")))"))

   :emacs/require-and-call-text
   (fn [feature fn-sym & args]
     (record! state :emacs/require-and-call-text (into [feature fn-sym] args))
     (str "(format \"%s\" (progn (require '" feature ") (" fn-sym
          (when (seq args) (str " " (str/join " " args))) ")))"))

   :emacs/require-and-call-plist-json
   (fn [feature fn-sym params-map]
     (record! state :emacs/require-and-call-plist-json [feature fn-sym params-map])
     (str "(json-encode (progn (require '" feature ") (" fn-sym " '"
          (pr-str params-map) ")))"))

   :emacs/daemon-store-ensure-default!
   (fn [] (record! state :emacs/daemon-store-ensure-default! [])
     (:default-daemon-id @state))

   :emacs/daemon-store-default-id
   (fn [] (record! state :emacs/daemon-store-default-id [])
     (:default-daemon-id @state))

   :emacs/daemon-store-select-for-ling
   (fn [ling-id] (record! state :emacs/daemon-store-select-for-ling [ling-id])
     (:default-daemon-id @state))

   :emacs/daemon-store-bind-ling!
   (fn [daemon-id slave-id]
     (record! state :emacs/daemon-store-bind-ling! [daemon-id slave-id])
     (swap! state assoc-in [:bindings slave-id] daemon-id)
     true)

   :emacs/daemon-store-unbind-ling!
   (fn [daemon-id slave-id]
     (record! state :emacs/daemon-store-unbind-ling! [daemon-id slave-id])
     (swap! state update :bindings dissoc slave-id)
     true)

   :emacs/daemon-store-get-for-ling
   (fn [slave-id] (record! state :emacs/daemon-store-get-for-ling [slave-id])
     (get-in @state [:bindings slave-id]))})

(defn install!
  "Register every :emacs/* key from STUB. Returns STUB."
  [stub]
  (ext/register-many! (handlers stub))
  stub)

(defn with-emacs
  "clojure.test fixture: install a default stub Emacs for the test, then
   restore whatever was registered under the :emacs/* keys before."
  [f]
  (let [prior (into {} (map (juxt identity ext/get-extension)) ext-keys)]
    (try
      (install! (->emacs))
      (f)
      (finally
        (doseq [[k v] prior]
          (if v (ext/register! k v) (ext/deregister! k)))))))

(defmacro with-stub-emacs
  "Bind SYM to an installed stub Emacs built from OPTS for BODY, restoring the
   previously registered :emacs/* keys afterwards."
  [[sym opts] & body]
  `(let [prior# (into {} (map (juxt identity ext/get-extension)) ext-keys)
         ~sym   (install! (->emacs ~opts))]
     (try
       ~@body
       (finally
         (doseq [[k# v#] prior#]
           (if v# (ext/register! k# v#) (ext/deregister! k#)))))))
