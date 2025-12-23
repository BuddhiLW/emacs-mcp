(ns evaluator-usage
  "Examples demonstrating EmacsCiderEvaluator and DirectNreplEvaluator usage."
  (:require [emacs-mcp.evaluator :as eval]))

;; ============================================================================
;; Basic Usage Examples
;; ============================================================================

(comment
  ;; Create an evaluator instance
  (def evaluator (eval/create-emacs-cider-evaluator))

  ;; Check if connected to CIDER
  (eval/connected? evaluator)
  ;; => true (if Emacs is running with CIDER)

  ;; Get detailed connection status
  (eval/get-status evaluator)
  ;; => {:connected true, :type "cider", :info {...}}

  ;; Silent evaluation - no REPL buffer output
  (eval/eval-silent evaluator "(+ 1 2)")
  ;; => {:success true, :result "3"}

  ;; Explicit evaluation - shows in REPL buffer
  (eval/eval-explicit evaluator "(println \"Hello from evaluator!\")")
  ;; => {:success true, :result "nil"}
  ;; (also displays "Hello from evaluator!" in REPL buffer)

  ;; Using eval-code with options
  (eval/eval-code evaluator "(range 5)" {:silent? true})
  ;; => {:success true, :result "(0 1 2 3 4)"}

  (eval/eval-code evaluator "(defn greet [name] (str \"Hello, \" name))" {:silent? false})
  ;; => {:success true, :result "#'user/greet"}
  )

;; ============================================================================
;; MCP Tool Integration Pattern
;; ============================================================================

(defn create-eval-tool-handler
  "Create an MCP tool handler that uses the evaluator.
   This demonstrates the Dependency Injection pattern."
  [evaluator]
  (fn handle-eval [{:keys [code interactive]
                    :or {interactive false}}]
    (let [opts {:silent? (not interactive)}
          result (if (:silent? opts)
                   (eval/eval-silent evaluator code)
                   (eval/eval-explicit evaluator code))]
      (if (:success result)
        {:type "text" :text (:result result)}
        {:type "text" :text (:error result) :isError true}))))

(comment
  ;; Create a handler with an evaluator instance
  (def my-eval-handler (create-eval-tool-handler evaluator))

  ;; Use it to handle requests
  (my-eval-handler {:code "(+ 1 2)" :interactive false})
  ;; => {:type "text", :text "3"}

  (my-eval-handler {:code "(println \"Interactive!\")" :interactive true})
  ;; => {:type "text", :text "nil"}
  ;; (also shows in REPL buffer)
  )

;; ============================================================================
;; Error Handling Examples
;; ============================================================================

(comment
  ;; Syntax error
  (eval/eval-silent evaluator "(+ 1")
  ;; => {:success false, :error "EOF while reading"}

  ;; Runtime error
  (eval/eval-silent evaluator "(/ 1 0)")
  ;; => {:success false, :error "Divide by zero"}

  ;; Undefined symbol
  (eval/eval-silent evaluator "undefined-var")
  ;; => {:success false, :error "Unable to resolve symbol: undefined-var"}

  ;; When CIDER is not loaded
  ;; (eval/get-status evaluator)
  ;; => {:connected false, :error "emacs-mcp-cider not loaded"}
  )

;; ============================================================================
;; Advanced Usage - Stateful Operations
;; ============================================================================

(comment
  ;; Define a namespace
  (eval/eval-explicit evaluator "(ns my.test.ns)")
  ;; => {:success true, :result "nil"}

  ;; Define a function in that namespace
  (eval/eval-explicit evaluator "(defn add [a b] (+ a b))")
  ;; => {:success true, :result "#'my.test.ns/add"}

  ;; Use the function
  (eval/eval-silent evaluator "(my.test.ns/add 10 20)")
  ;; => {:success true, :result "30"}

  ;; Define state
  (eval/eval-explicit evaluator "(def counter (atom 0))")
  ;; => {:success true, :result "#'my.test.ns/counter"}

  ;; Modify state
  (eval/eval-silent evaluator "(swap! my.test.ns/counter inc)")
  ;; => {:success true, :result "1"}

  (eval/eval-silent evaluator "(swap! my.test.ns/counter inc)")
  ;; => {:success true, :result "2"}

  ;; Read state
  (eval/eval-silent evaluator "@my.test.ns/counter")
  ;; => {:success true, :result "2"}
  )

;; ============================================================================
;; Integration with Application State
;; ============================================================================

(defn make-stateful-evaluator-service
  "Create a service that wraps the evaluator with application state.
   This demonstrates how to integrate with larger applications."
  []
  (let [evaluator (eval/create-emacs-cider-evaluator)
        state (atom {:eval-count 0
                     :last-result nil
                     :history []})]
    {:evaluator evaluator
     :state state
     :eval! (fn [code opts]
              (let [result (if (:silent? opts)
                             (eval/eval-silent evaluator code)
                             (eval/eval-explicit evaluator code))]
                (swap! state update :eval-count inc)
                (swap! state assoc :last-result result)
                (swap! state update :history conj
                       {:code code
                        :opts opts
                        :result result
                        :timestamp (System/currentTimeMillis)})
                result))
     :stats (fn [] @state)
     :clear-history! (fn [] (swap! state assoc :history []))}))

(comment
  ;; Create the service
  (def eval-service (make-stateful-evaluator-service))

  ;; Evaluate with tracking
  ((:eval! eval-service) "(+ 1 2)" {:silent? true})
  ;; => {:success true, :result "3"}

  ((:eval! eval-service) "(* 3 4)" {:silent? true})
  ;; => {:success true, :result "12"}

  ;; Check statistics
  ((:stats eval-service))
  ;; => {:eval-count 2,
  ;;     :last-result {:success true, :result "12"},
  ;;     :history [{:code "(+ 1 2)", :opts {:silent? true}, :result {...}, :timestamp ...}
  ;;               {:code "(* 3 4)", :opts {:silent? true}, :result {...}, :timestamp ...}]}

  ;; Clear history
  ((:clear-history! eval-service)))

;; ============================================================================
;; Testing Pattern - Mock Evaluator
;; ============================================================================

(comment
  ;; For testing, you can create a simple mock that implements ReplEvaluator:

  (defrecord MockEvaluator [responses]
    eval/ReplEvaluator
    (eval-code [_this code]
      (get @responses code {:success true :result "mock-result"}))
    (connected? [_this] true)
    (get-status [_this] {:connected true :type :mock}))

  (defn make-mock-evaluator [response-map]
    (->MockEvaluator (atom response-map)))

  ;; Usage:
  (def mock-eval (make-mock-evaluator
                  {"(+ 1 2)" {:success true :result "3"}
                   "(* 2 3)" {:success true :result "6"}}))

  (eval/eval-code mock-eval "(+ 1 2)")
  ;; => {:success true, :result "3"}

  (eval/eval-code mock-eval "(* 2 3)")
  ;; => {:success true, :result "6"}

  (eval/connected? mock-eval)
  ;; => true
  )
