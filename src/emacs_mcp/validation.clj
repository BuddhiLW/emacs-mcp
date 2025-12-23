(ns emacs-mcp.validation
  "Input validation for REPL evaluation requests.
   
   Following CLARITY principle: 'Inputs are guarded' - validate at system boundaries.
   
   Validates:
   - Code must be non-empty string
   - Mode must be :silent or :explicit (if provided)
   - Port must be valid port number (1-65535) if provided
   - Timeout must be positive integer if provided"
  (:require [clojure.string :as str]))

(defn validate-code
  "Validate that code is a non-empty string.
   
   Throws ex-info with :validation type on failure."
  [code]
  (when (nil? code)
    (throw (ex-info "Code is required"
                    {:type :validation
                     :field :code
                     :reason :missing})))
  (when-not (string? code)
    (throw (ex-info "Code must be a string"
                    {:type :validation
                     :field :code
                     :reason :invalid-type
                     :received (type code)})))
  (when (str/blank? code)
    (throw (ex-info "Code cannot be empty or blank"
                    {:type :validation
                     :field :code
                     :reason :blank}))))

(defn validate-mode
  "Validate that mode is :silent or :explicit (if provided).
   
   Throws ex-info with :validation type on failure."
  [mode]
  (when mode
    (when-not (keyword? mode)
      (throw (ex-info "Mode must be a keyword"
                      {:type :validation
                       :field :mode
                       :reason :invalid-type
                       :received (type mode)})))
    (when-not (#{:silent :explicit} mode)
      (throw (ex-info "Invalid mode"
                      {:type :validation
                       :field :mode
                       :reason :invalid-value
                       :valid #{:silent :explicit}
                       :received mode})))))

(defn validate-port
  "Validate that port is a valid port number (1-65535) if provided.
   
   Throws ex-info with :validation type on failure."
  [port]
  (when port
    (when-not (integer? port)
      (throw (ex-info "Port must be an integer"
                      {:type :validation
                       :field :port
                       :reason :invalid-type
                       :received (type port)})))
    (when-not (<= 1 port 65535)
      (throw (ex-info "Port must be between 1 and 65535"
                      {:type :validation
                       :field :port
                       :reason :out-of-range
                       :valid-range [1 65535]
                       :received port})))))

(defn validate-timeout
  "Validate that timeout is a positive integer if provided.
   
   Throws ex-info with :validation type on failure."
  [timeout]
  (when timeout
    (when-not (integer? timeout)
      (throw (ex-info "Timeout must be an integer"
                      {:type :validation
                       :field :timeout
                       :reason :invalid-type
                       :received (type timeout)})))
    (when-not (pos? timeout)
      (throw (ex-info "Timeout must be positive"
                      {:type :validation
                       :field :timeout
                       :reason :invalid-value
                       :received timeout})))))

(defn validate-buffer-name
  "Validate that buffer_name is a non-empty string if provided.
   
   Throws ex-info with :validation type on failure."
  [buffer-name]
  (when buffer-name
    (when-not (string? buffer-name)
      (throw (ex-info "Buffer name must be a string"
                      {:type :validation
                       :field :buffer_name
                       :reason :invalid-type
                       :received (type buffer-name)})))
    (when (str/blank? buffer-name)
      (throw (ex-info "Buffer name cannot be empty or blank"
                      {:type :validation
                       :field :buffer_name
                       :reason :blank})))))

(defn validate-line-number
  "Validate that line is a positive integer if provided.
   
   Throws ex-info with :validation type on failure."
  [line]
  (when line
    (when-not (integer? line)
      (throw (ex-info "Line number must be an integer"
                      {:type :validation
                       :field :line
                       :reason :invalid-type
                       :received (type line)})))
    (when-not (pos? line)
      (throw (ex-info "Line number must be positive"
                      {:type :validation
                       :field :line
                       :reason :invalid-value
                       :received line})))))

(defn validate-eval-request
  "Validate a REPL evaluation request.
   
   Validates all fields and throws ex-info with :validation type on first failure.
   
   Required:
   - code: non-empty string
   
   Optional:
   - mode: :silent or :explicit
   - port: integer 1-65535
   - timeout: positive integer
   
   Example:
   (validate-eval-request {:code \"(+ 1 2)\"})
   (validate-eval-request {:code \"(println 1)\" :mode :silent :port 7888 :timeout 5000})
   
   Throws:
   (ex-info \"Code is required\" {:type :validation :field :code :reason :missing})
   (ex-info \"Invalid mode\" {:type :validation :field :mode :reason :invalid-value ...})"
  [{:keys [code mode port timeout]}]
  (validate-code code)
  (validate-mode mode)
  (validate-port port)
  (validate-timeout timeout))

(defn validate-cider-eval-request
  "Validate a CIDER evaluation request (code only required).
   
   Validates code field and throws ex-info with :validation type on failure.
   
   Example:
   (validate-cider-eval-request {:code \"(+ 1 2)\"})
   
   Throws:
   (ex-info \"Code is required\" {:type :validation :field :code :reason :missing})"
  [{:keys [code]}]
  (validate-code code))

(defn validate-buffer-request
  "Validate a buffer operation request (buffer_name required).
   
   Validates buffer_name field and throws ex-info with :validation type on failure.
   
   Example:
   (validate-buffer-request {:buffer_name \"*scratch*\"})
   
   Throws:
   (ex-info \"Buffer name cannot be empty\" {:type :validation :field :buffer_name ...})"
  [{:keys [buffer_name]}]
  (validate-buffer-name buffer_name))

(defn validate-goto-line-request
  "Validate a goto-line request (line number required).
   
   Validates line field and throws ex-info with :validation type on failure.
   
   Example:
   (validate-goto-line-request {:line 42})
   
   Throws:
   (ex-info \"Line number must be positive\" {:type :validation :field :line ...})"
  [{:keys [line]}]
  (validate-line-number line))

(defn wrap-validation-error
  "Wrap a validation error for MCP response format.

   Converts ex-info validation errors into user-friendly error responses.

   Returns map with :type, :text, and :isError keys suitable for MCP response."
  [e]
  (let [data (ex-data e)]
    {:type "text"
     :text (str "Validation error: " (.getMessage e)
                (when-let [field (:field data)]
                  (str " (field: " (name field) ")"))
                (when-let [valid (:valid data)]
                  (str " (valid values: " valid ")"))
                (when-let [range (:valid-range data)]
                  (str " (valid range: " (first range) "-" (second range) ")")))
     :isError true}))

(defn escape-elisp-string
  "Escape a string for safe inclusion in Elisp code.

   Escapes backslashes and double quotes for Elisp string literals.

   Example:
   (escape-elisp-string \"hello \\\"world\\\"\")
   => \"hello \\\\\\\"world\\\\\\\"\""
  [s]
  (when s
    (-> s
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\""))))
