(ns emacs-mcp.evaluator
  "REPL evaluation protocols and implementations.

  Provides abstraction for evaluating Clojure code via different mechanisms:
  - DirectNreplEvaluator: Direct nREPL socket connection (SILENT/FAST mode)
  - EmacsCiderEvaluator: Via emacsclient to CIDER (EXPLICIT/INTERACTIVE mode)
  - Future: BabashkaEvaluator, etc.

  Follows SOLID principles:
  - DIP: Depend on ReplEvaluator protocol abstraction
  - SRP: Each evaluator handles only one type of communication
  - OCP: Extend via new protocol implementations, not modification"
  (:require [bencode.core :as bencode]
            [emacs-mcp.emacsclient :as ec]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.net Socket InetSocketAddress]
           [java.io BufferedOutputStream PushbackInputStream]))

;; ============================================================================
;; Protocol Definition
;; ============================================================================

(defprotocol ReplEvaluator
  "Protocol for REPL evaluation backends.

  All evaluators should support:
  - eval-code: Synchronous code evaluation
  - connected?: Connection status check
  - get-status: Detailed connection information"

  (eval-code [this code]
    "Evaluate Clojure code and return result.

    Returns map with:
      {:success true/false
       :result string         ; evaluation result or nil
       :value string          ; last value (for nREPL :value field)
       :out string            ; stdout output
       :err string            ; stderr output
       :ns string             ; current namespace
       :error string}         ; error message if failed")

  (connected? [this]
    "Check if evaluator is connected and ready.
    Returns boolean.")

  (get-status [this]
    "Get detailed status information.

    Returns map with:
      {:connected boolean
       :host string
       :port number
       :type keyword         ; :nrepl, :cider, etc.
       :session-id string    ; if applicable
       :ns string            ; current namespace
       :error string}        ; error message if any"))

;; ============================================================================
;; nREPL Message Encoding/Decoding (from clojure-mcp-light)
;; ============================================================================

(defmulti bytes->str
  "Recursively convert byte arrays to strings in nested structures"
  class)

(defmethod bytes->str :default
  [x]
  x)

(defmethod bytes->str (Class/forName "[B")
  [^bytes x]
  (String. x "UTF-8"))

(defmethod bytes->str clojure.lang.IPersistentVector
  [v]
  (mapv bytes->str v))

(defmethod bytes->str clojure.lang.IPersistentMap
  [m]
  (->> m
       (map (fn [[k v]] [(bytes->str k) (bytes->str v)]))
       (into {})))

(defn read-nrepl-msg
  "Decode a raw bencode message map into a Clojure map with keyword keys.
  Recursively converts all byte arrays to strings."
  [msg]
  (let [decoded (bytes->str msg)]
    (zipmap (map keyword (keys decoded))
            (vals decoded))))

(defn write-nrepl-msg
  "Write bencode message to output stream and flush"
  [out msg]
  (bencode/write-bencode out msg)
  (.flush out))

(defn next-msg-id
  "Generate unique message ID"
  []
  (str (java.util.UUID/randomUUID)))

;; ============================================================================
;; nREPL Message Processing
;; ============================================================================

(defn message-seq
  "Create lazy sequence of raw bencode messages from input stream.
  Continues until EOF or error. Returns nils after stream ends."
  [in]
  (repeatedly #(bencode/read-bencode in)))

(defn decode-messages
  "Map raw bencode message sequence through decoder.
  Stops at first nil (EOF/error)."
  [msg-seq]
  (map read-nrepl-msg (take-while some? msg-seq)))

(defn take-until-done
  "Take messages up to and including first with 'done' status."
  [msg-seq]
  (letfn [(take-upto [pred coll]
            (lazy-seq
             (when-let [s (seq coll)]
               (let [x (first s)]
                 (cons x (when-not (pred x)
                           (take-upto pred (rest s))))))))]
    (take-upto #(some #{"done"} (:status %)) msg-seq)))

(defn collect-eval-response
  "Collect and merge nREPL eval response messages.

  Combines multiple response messages into single result map:
  - Accumulates :value into vector
  - Concatenates :out and :err strings
  - Takes last :ns
  - Collects :status into set"
  [messages]
  (reduce
   (fn [acc msg]
     (cond-> acc
       (:value msg)
       (update :value (fnil conj []) (:value msg))

       (:out msg)
       (update :out str (:out msg))

       (:err msg)
       (update :err str (:err msg))

       (:ns msg)
       (assoc :ns (:ns msg))

       (:status msg)
       (update :status (fnil into #{}) (:status msg))))
   {}
   messages))

;; ============================================================================
;; DirectNreplEvaluator Implementation
;; ============================================================================

(defrecord DirectNreplEvaluator [host port timeout-ms session-id]
  ReplEvaluator

  (eval-code [this code]
    (log/debug "DirectNreplEvaluator: eval-code on" host ":" port)
    (try
      (let [socket (doto (Socket.)
                     (.connect (InetSocketAddress. host (int port))
                               (int timeout-ms))
                     (.setSoTimeout (int timeout-ms)))
            out (BufferedOutputStream. (.getOutputStream socket))
            in (PushbackInputStream. (.getInputStream socket))
            msg-id (next-msg-id)
            eval-msg (cond-> {"op" "eval"
                              "code" code
                              "id" msg-id}
                       session-id (assoc "session" session-id))]

        ;; Send eval message
        (write-nrepl-msg out eval-msg)

        ;; Collect response messages
        (let [msg-stream (message-seq in)
              decoded (decode-messages msg-stream)
              filtered (filter #(= (:id %) msg-id) decoded)
              responses (doall (take-until-done filtered))
              result (collect-eval-response responses)

              ;; Check for errors
              has-error? (or (contains? (:status result) "error")
                             (seq (:err result)))

              ;; Get the last value as result string
              last-value (when-let [vals (:value result)]
                           (last vals))]

          ;; Close socket
          (.close socket)

          {:success (not has-error?)
           :result last-value
           :value last-value
           :out (or (:out result) "")
           :err (or (:err result) "")
           :ns (or (:ns result) "user")
           :error (when has-error?
                    (or (:err result) "Evaluation error"))}))

      (catch java.net.SocketTimeoutException e
        (log/warn "DirectNreplEvaluator: timeout" (.getMessage e))
        {:success false
         :result nil
         :value nil
         :out ""
         :err ""
         :ns "user"
         :error (str "Timeout: " (.getMessage e))})

      (catch Exception e
        (log/warn "DirectNreplEvaluator: error" (.getMessage e))
        {:success false
         :result nil
         :value nil
         :out ""
         :err ""
         :ns "user"
         :error (str "Error: " (.getMessage e))})))

  (connected? [this]
    (try
      (let [socket (doto (Socket.)
                     (.connect (InetSocketAddress. host (int port)) 500)
                     (.setSoTimeout 500))
            out (BufferedOutputStream. (.getOutputStream socket))
            in (PushbackInputStream. (.getInputStream socket))
            msg-id (next-msg-id)]

        ;; Try describe op to check connection
        (write-nrepl-msg out {"op" "describe" "id" msg-id})

        (let [msg-stream (message-seq in)
              decoded (decode-messages msg-stream)
              filtered (filter #(= (:id %) msg-id) decoded)
              responses (doall (take 5 filtered))]
          (.close socket)
          (boolean (seq responses))))

      (catch Exception e
        (log/debug "DirectNreplEvaluator: not connected" (.getMessage e))
        false)))

  (get-status [this]
    {:connected (connected? this)
     :host host
     :port port
     :type :nrepl
     :session-id session-id
     :timeout-ms timeout-ms}))

;; ============================================================================
;; Constructor Functions
;; ============================================================================

(defn create-direct-nrepl-evaluator
  "Create a DirectNreplEvaluator instance.

  Options:
    :host - nREPL host (default: localhost)
    :port - nREPL port (default: 7910)
    :timeout-ms - Socket timeout in milliseconds (default: 120000)
    :session-id - Optional nREPL session ID for persistent sessions

  Example:
    (create-direct-nrepl-evaluator {:port 7910})
    (create-direct-nrepl-evaluator {:host \"localhost\" :port 7910 :timeout-ms 5000})"
  [{:keys [host port timeout-ms session-id]
    :or {host "localhost"
         port 7910
         timeout-ms 120000}}]
  (->DirectNreplEvaluator host port timeout-ms session-id))

(defn default-evaluator
  "Create a default evaluator (DirectNreplEvaluator on port 7910)"
  []
  (create-direct-nrepl-evaluator {:port 7910}))

;; ============================================================================
;; EmacsCiderEvaluator Implementation (EXPLICIT/INTERACTIVE mode)
;; ============================================================================

(defrecord EmacsCiderEvaluator []
  ReplEvaluator

  (eval-code [_this code]
    (log/debug "EmacsCiderEvaluator: eval-code (explicit mode)")
    (let [elisp (format "(require 'emacs-mcp-cider nil t)
                         (if (fboundp 'emacs-mcp-cider-eval-explicit)
                             (emacs-mcp-cider-eval-explicit %s)
                           (error \"emacs-mcp-cider not loaded\"))"
                        (pr-str code))
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if success
        {:success true
         :result result
         :value result
         :out ""
         :err ""
         :ns "user"}
        {:success false
         :result nil
         :value nil
         :out ""
         :err error
         :ns "user"
         :error error})))

  (connected? [this]
    (let [{:keys [connected]} (get-status this)]
      (boolean connected)))

  (get-status [_this]
    (log/debug "EmacsCiderEvaluator: checking status")
    (let [elisp "(require 'emacs-mcp-cider nil t)
                 (if (fboundp 'emacs-mcp-cider-status)
                     (json-encode (emacs-mcp-cider-status))
                   (json-encode (list :connected nil :error \"emacs-mcp-cider not loaded\")))"
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if success
        (try
          (let [status (json/read-str result :key-fn keyword)]
            (assoc status :type :cider))
          (catch Exception e
            {:connected false
             :type :cider
             :error (str "Failed to parse status: " (.getMessage e))
             :raw-result result}))
        {:connected false
         :type :cider
         :error (or error "Unknown error checking CIDER status")}))))

(defn create-emacs-cider-evaluator
  "Create an EmacsCiderEvaluator instance.
   
   This evaluator uses emacsclient to communicate with a running Emacs instance
   that has CIDER and emacs-mcp-cider.el loaded.
   
   This is the EXPLICIT/INTERACTIVE mode evaluator - it shows evaluation
   results in the CIDER REPL buffer with full interactive feedback.
   
   For SILENT/FAST evaluation without UI feedback, use DirectNreplEvaluator.
   
   Example:
     (def evaluator (create-emacs-cider-evaluator))
     (eval-code evaluator \"(+ 1 2)\")
     (connected? evaluator)"
  []
  (->EmacsCiderEvaluator))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn eval-silent
  "Evaluate code using DirectNreplEvaluator (silent/fast mode).
   Creates a temporary evaluator instance.
   
   For repeated evaluations, create an evaluator instance instead."
  ([code]
   (eval-silent code {}))
  ([code {:keys [port] :or {port 7910}}]
   (let [evaluator (create-direct-nrepl-evaluator {:port port})]
     (eval-code evaluator code))))

(defn eval-explicit
  "Evaluate code using EmacsCiderEvaluator (explicit/interactive mode).
   Creates a temporary evaluator instance.
   
   Shows evaluation in CIDER REPL buffer with full feedback.
   
   For repeated evaluations, create an evaluator instance instead."
  [code]
  (let [evaluator (create-emacs-cider-evaluator)]
    (eval-code evaluator code)))

(comment
  ;; Usage examples

  ;; Create evaluator
  (def evaluator (create-direct-nrepl-evaluator {:port 7910}))

  ;; Check connection
  (connected? evaluator)

  ;; Get status
  (get-status evaluator)

  ;; Evaluate code
  (eval-code evaluator "(+ 1 2 3)")

  ;; With session
  (def evaluator-with-session
    (create-direct-nrepl-evaluator {:port 7910
                                    :session-id "some-session-id"}))

  (eval-code evaluator-with-session "(def x 42)")
  (eval-code evaluator-with-session "x"))
