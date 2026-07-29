(ns hive-mcp.agent.openrouter
  "OpenAI-compatible LLM backend with multi-provider support.

   Supports any provider using the OpenAI /v1/chat/completions shape:
   OpenRouter, Venice AI, Groq, Together, Fireworks, OpenAI, local Ollama.
   Auto-discovers available providers by checking configured API keys."
  (:require [hive-mcp.agent.protocol :as proto]
            [hive-mcp.config.core :as global-config]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ---------------------------------------------------------------------------
;;; Provider Registry
;;; ---------------------------------------------------------------------------

(def provider-registry
  "Known LLM providers.

   `:anthropic` is special — it uses Anthropic's native Messages API
   (OAuth when available, else API key) via hive-agent.llm.anthropic.
   The `:dispatch :anthropic-oauth` marker tells the spawn plumbing to
   route through the anthropic HTTP client rather than the OpenAI-compat
   path. All others hit OpenAI-compat /v1/chat/completions endpoints."
  {:anthropic     {:dispatch      :anthropic-oauth
                   :secret-key    :anthropic-api-key
                   :default-model "claude-sonnet-4-6"}
   :openrouter    {:api-url       "https://openrouter.ai/api/v1/chat/completions"
                   :secret-key    :openrouter-api-key
                   :default-model "anthropic/claude-3-haiku"}
   :venice        {:api-url       "https://api.venice.ai/api/v1/chat/completions"
                   :secret-key    :venice-api-key
                   :default-model "venice-uncensored"}
   :groq          {:api-url       "https://api.groq.com/openai/v1/chat/completions"
                   :secret-key    :groq-api-key
                   :default-model "llama-3.3-70b-versatile"}
   :together      {:api-url       "https://api.together.xyz/v1/chat/completions"
                   :secret-key    :together-api-key
                   :default-model "meta-llama/Llama-3.3-70B-Instruct-Turbo"}
   :fireworks     {:api-url       "https://api.fireworks.ai/inference/v1/chat/completions"
                   :secret-key    :fireworks-api-key
                   :default-model "accounts/fireworks/models/llama-v3p3-70b-instruct"}
   :openai        {:api-url       "https://api.openai.com/v1/chat/completions"
                   :secret-key    :openai-api-key
                   :default-model "gpt-4o-mini"}
   :ollama-compat {:api-url       "http://localhost:11434/v1/chat/completions"
                   :secret-key    nil
                   :default-model "devstral-small:24b"}})

(def ChatCompletionsUrl
  "Malli schema for an OpenAI-compatible chat-completions endpoint URL."
  [:and
   [:string {:gen/fmap (fn [s]
                         (let [host (str/replace (str s) #"[^a-zA-Z0-9]" "")]
                           (str "https://api." (if (str/blank? host) "example" host)
                                ".test/v1/chat/completions")))}]
   [:fn {:error/message "must be a URL ending in /chat/completions"}
    (fn [s] (and (string? s) (str/ends-with? s "/chat/completions")))]])

(def ProviderEntry
  "Malli schema for one `provider-registry` entry, dispatched on `:dispatch`.

   :anthropic-oauth branch — native Anthropic Messages API. Carries NO
     :api-url: it is not an OpenAI-compat chat-completions endpoint, and
     `openai-compat-backend` refuses it.
   default branch (no :dispatch) — OpenAI-compat provider: :api-url is
     REQUIRED and must end in \"/chat/completions\"; :secret-key may be nil
     (nil = no auth needed, e.g. :ollama-compat).

   Both branches are open maps: config overrides may add keys such as
   :available-models."
  [:multi {:dispatch :dispatch}
   [:anthropic-oauth
    [:map
     [:dispatch [:= :anthropic-oauth]]
     [:secret-key :keyword]
     [:default-model :string]
     [:available-models {:optional true} [:sequential :string]]]]
   [::m/default
    [:map
     [:api-url ChatCompletionsUrl]
     [:secret-key [:maybe :keyword]]
     [:default-model :string]
     [:available-models {:optional true} [:sequential :string]]]]])

(def ProviderRegistry
  "Malli schema for the provider registry: provider keyword -> ProviderEntry."
  [:map-of :keyword ProviderEntry])

(defn valid-provider-entry?
  "True when `entry` conforms to `ProviderEntry`."
  [entry]
  (m/validate ProviderEntry entry))

(def provider-priority
  "Provider preference order for auto-discovery.

   Every keyword here MUST resolve to a `provider-registry` entry:
   `available-providers` reads that entry's :secret-key, and a missing entry
   reads as nil = no auth needed, silently promoting a phantom provider."
  [:openrouter :venice :groq :together :fireworks :openai :ollama-compat])

(declare best-available-provider)

(defn effective-provider-registry
  "Return provider-registry merged with config :llm-providers overrides.
   Config values win; static registry is the fallback."
  []
  (let [config-providers (global-config/get-config-value "llm-providers")]
    (if (map? config-providers)
      (reduce-kv (fn [acc k v]
                   (if (map? v)
                     (update acc k merge v)
                     acc))
                 provider-registry
                 config-providers)
      provider-registry)))

(defn validate-provider
  "Validate provider keyword. Returns nil on success, error map on failure."
  [provider]
  (let [reg (effective-provider-registry)]
    (when-not (contains? reg provider)
      {:error :unknown-provider
       :requested provider
       :available (vec (keys reg))
       :fix "Use one of the available providers, or add via: hive config set llm-providers.<name>.api-url <url>"})))

(defn validate-model
  "Validate model for a provider. Returns nil on success, error map on failure."
  [provider model]
  (let [reg (effective-provider-registry)
        entry (get reg provider)
        available (:available-models entry)]
    (when (and (seq available) (not (some #{model} available)))
      {:error :unknown-model-for-provider
       :provider provider
       :model model
       :available (vec available)
       :fix (str "Use one of the available models for " (name provider)
                 ", or add via: hive config set llm-providers." (name provider)
                 ".available-models [...]")})))

(defn- parse-model-prefix
  "Parse optional '<provider>:<model>' prefix. Returns [provider-kw-or-nil clean-model].
   Only recognizes prefixes that match a known provider in the effective registry."
  [model]
  (if-let [idx (and (string? model) (str/index-of model ":"))]
    (let [prefix (subs model 0 idx)
          rest   (subs model (inc idx))
          prov-kw (keyword prefix)]
      (if (contains? (effective-provider-registry) prov-kw)
        [prov-kw rest]
        [nil model]))
    [nil model]))

(defn- claude-model-name?
  "True if the model string identifies a native Anthropic Claude model.
   Routes directly through Anthropic OAuth/API (bypassing OpenRouter relay).
   Strips `anthropic/` prefix so `anthropic/claude-sonnet-4-6` and
   `claude-sonnet-4-6` both match."
  [model]
  (when (string? model)
    (let [clean (if (str/starts-with? model "anthropic/")
                  (subs model (count "anthropic/"))
                  model)]
      (boolean (re-find #"^claude-" clean)))))

(defn- strip-anthropic-prefix
  "Strip the `anthropic/` prefix from a model name so native Anthropic API
   receives `claude-sonnet-4-6` rather than `anthropic/claude-sonnet-4-6`
   (the latter is OpenRouter's naming scheme, not Anthropic's)."
  [model]
  (if (and (string? model) (str/starts-with? model "anthropic/"))
    (subs model (count "anthropic/"))
    model))

(defn resolve-provider-model
  "Resolve provider + model for an agent spawn/wave.
   Resolution order (first match wins):
     0. Explicit :provider kwarg                — caller forced routing
     1. '<provider>:<model>' prefix in :model   — e.g. 'venice:qwen3-...'
     2. Claude model-name auto-detection        — routes to :anthropic OAuth
     3. agent-defaults :provider from config
     4. best-available-provider (first configured API key)

   Explicit routing (steps 0–1) always wins over Claude auto-detection.
   This is the privacy escape hatch: callers who need Claude models called
   through an anonymity-preserving relay (e.g. Venice, OpenRouter) can pass
   either `:provider :venice` OR prefix the model as `venice:claude-...` —
   both bypass the OAuth shortcut. This keeps in-plan Claude billing the
   default for normal use while preserving the privacy case.

   Claude auto-detection (step 2) matches `anthropic/claude-*` and bare
   `claude-*` model names, stripping the `anthropic/` prefix since the
   native Messages API uses unprefixed names (`claude-sonnet-4-6`, not
   `anthropic/claude-sonnet-4-6` — the latter is OpenRouter's schema).

   Returns {:provider <kw> :model <str>} or throws on validation failure."
  [{:keys [provider model agent-type]}]
  (let [explicit-prov  (some-> provider keyword)
        [prefix-prov clean-model] (parse-model-prefix model)
        agent-defaults (global-config/get-config-value "agent-defaults")
        type-defaults  (get agent-defaults (keyword agent-type))
        ;; Resolve the candidate model BEFORE deciding the provider so that
        ;; Claude-model auto-detection can fire even when the caller passed
        ;; no :model (type-defaults supplies it).
        candidate-model (or clean-model
                            (:model type-defaults))
        ;; Claude auto-detection is suppressed when explicit routing is
        ;; present (explicit :provider or '<prov>:<model>' prefix). This
        ;; preserves the privacy escape hatch — e.g. venice:claude-sonnet
        ;; or {:provider :venice :model "claude-..."} stays on venice.
        explicit-routing? (or explicit-prov prefix-prov)
        claude?           (and (not explicit-routing?)
                               (claude-model-name? candidate-model))
        eff-provider   (or explicit-prov
                           prefix-prov
                           (when claude? :anthropic)
                           (some-> type-defaults :provider keyword)
                           (best-available-provider))
        reg            (effective-provider-registry)
        reg-entry      (get reg eff-provider)
        eff-model      (cond
                         claude? (strip-anthropic-prefix candidate-model)
                         :else   (or candidate-model
                                     (:default-model reg-entry)))]
    ;; Validate
    (when-let [err (validate-provider eff-provider)]
      (throw (ex-info (str "Unknown provider: " (name eff-provider)) err)))
    (when-let [err (validate-model eff-provider eff-model)]
      (log/warn "Model not in available-models list" err))
    {:provider eff-provider :model eff-model}))

;;; ---------------------------------------------------------------------------
;;; Metrics
;;; ---------------------------------------------------------------------------

(def ^:private timeout-ms
  "HTTP timeout in milliseconds (5 minutes)."
  300000)

(defonce metrics
  (atom {:request-count 0
         :success-count 0
         :error-count 0
         :timeout-count 0
         :total-latency-ms 0}))

(defn reset-metrics!
  "Reset all metrics to zero."
  []
  (reset! metrics {:request-count 0
                   :success-count 0
                   :error-count 0
                   :timeout-count 0
                   :total-latency-ms 0}))

(defn get-metrics
  "Get current metrics snapshot with computed averages."
  []
  (let [m @metrics
        req-count (:request-count m)]
    (assoc m
           :avg-latency-ms (if (pos? req-count)
                             (/ (:total-latency-ms m) req-count)
                             0)
           :error-rate (if (pos? req-count)
                         (double (/ (:error-count m) req-count))
                         0.0))))

(defn- record-request! []
  (swap! metrics update :request-count inc))

(defn- record-success! [latency-ms]
  (swap! metrics #(-> %
                      (update :success-count inc)
                      (update :total-latency-ms + latency-ms))))

(defn- record-error! [latency-ms]
  (swap! metrics #(-> %
                      (update :error-count inc)
                      (update :total-latency-ms + latency-ms))))

(defn- record-timeout! [latency-ms]
  (swap! metrics #(-> %
                      (update :timeout-count inc)
                      (update :error-count inc)
                      (update :total-latency-ms + latency-ms))))

;;; ---------------------------------------------------------------------------
;;; Request/Response (OpenAI-compatible shape)
;;; ---------------------------------------------------------------------------

(defn- format-tools
  "Convert a tool schema vector to OpenAI function-calling format.

   Accepts two input shapes (shape-tolerant):
     1. hive-mcp internal:  {:name _ :description _ :inputSchema _}
     2. hive-agent OpenAI:  {:type \"function\" :function {:name _ :description _ :parameters _}}

   Already-OpenAI entries pass through unchanged. This prevents nil-field 400s
   from strict providers (venice) when hive-agent definitions bypass the
   internal registry shape."
  [tools]
  (when (seq tools)
    (mapv (fn [t]
            (cond
              ;; Already OpenAI function-calling shape — return as-is.
              (and (= "function" (:type t)) (map? (:function t)))
              t

              ;; hive-mcp internal shape.
              (and (:name t) (:inputSchema t))
              {:type "function"
               :function {:name        (:name t)
                          :description (:description t)
                          :parameters  (:inputSchema t)}}

              :else
              (throw (ex-info "format-tools: unrecognized tool schema shape"
                              {:tool t}))))
          tools)))

(defn- parse-tool-calls
  "Parse OpenAI-format tool calls to internal format."
  [tool-calls]
  (mapv (fn [tc]
          {:id (:id tc)
           :name (get-in tc [:function :name])
           :arguments (json/read-str (get-in tc [:function :arguments]) :key-fn keyword)})
        tool-calls))

(defn parse-response
  "Parse OpenAI-compatible response message into internal format."
  [choice]
  (if (nil? choice)
    {:type :error :error "Provider returned nil message"}
    (let [tool-calls (:tool_calls choice)
          content (:content choice)]
      (cond
        (seq tool-calls)
        {:type :tool_calls
         :calls (parse-tool-calls tool-calls)}

        (str/blank? content)
        {:type :error
         :error (str "Provider returned empty response"
                     (when content " (whitespace-only)"))}

        :else
        {:type :text
         :content content}))))

(defn- chat-request
  "Make chat completion request to an OpenAI-compatible endpoint."
  [endpoint-url api-key model messages tools provider-name]
  (let [start-ms (System/currentTimeMillis)
        msg-count (count messages)
        tool-count (count tools)]

    (log/debug (str provider-name " request starting")
               {:model model :messages msg-count :tools tool-count})
    (record-request!)

    (try
      (let [body (cond-> {:model model
                          :messages messages}
                   (seq tools) (assoc :tools (format-tools tools)))
            response (http/post endpoint-url
                                {:headers (cond-> {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}
                                            (= provider-name "openrouter")
                                            (assoc "HTTP-Referer" "https://github.com/BuddhiLW/hive-mcp"))
                                 :body (json/write-str body)
                                 :as :json
                                 :socket-timeout timeout-ms
                                 :connection-timeout timeout-ms
                                 :throw-exceptions false})
            elapsed-ms (- (System/currentTimeMillis) start-ms)
            status (:status response)]

        (cond
          (nil? status)
          (do
            (record-timeout! elapsed-ms)
            (log/error (str provider-name " request failed: no response")
                       {:model model :elapsed-ms elapsed-ms})
            (throw (ex-info (str provider-name " request failed: no response")
                            {:model model :elapsed-ms elapsed-ms :provider provider-name})))

          (not (<= 200 status 299))
          (let [error-body (try (json/read-str (or (:body response) "{}") :key-fn keyword)
                                (catch Exception _ {}))]
            (record-error! elapsed-ms)
            (log/error (str provider-name " API error")
                       {:status status :error error-body :model model :elapsed-ms elapsed-ms})
            (throw (ex-info (str provider-name " API error: " status " - "
                                 (or (:message (:error error-body)) "unknown error"))
                            {:status status :error error-body :model model
                             :elapsed-ms elapsed-ms :provider provider-name})))

          :else
          (do
            (record-success! elapsed-ms)
            (log/info (str provider-name " request completed")
                      {:model model :status status :elapsed-ms elapsed-ms})
            (:body response))))

      (catch java.net.SocketTimeoutException e
        (let [elapsed-ms (- (System/currentTimeMillis) start-ms)]
          (record-timeout! elapsed-ms)
          (log/error (str provider-name " request timed out")
                     {:model model :elapsed-ms elapsed-ms :timeout-ms timeout-ms})
          (throw (ex-info (str provider-name " request timed out")
                          {:model model :elapsed-ms elapsed-ms :timeout-ms timeout-ms
                           :provider provider-name}
                          e))))

      (catch Exception e
        (when-not (ex-data e) ;; don't re-wrap our own ex-infos
          (let [elapsed-ms (- (System/currentTimeMillis) start-ms)]
            (record-error! elapsed-ms)
            (log/error e (str provider-name " request exception")
                       {:model model :elapsed-ms elapsed-ms})))
        (throw e)))))

;;; ---------------------------------------------------------------------------
;;; OpenAICompatBackend Record
;;; ---------------------------------------------------------------------------

(defrecord OpenAICompatBackend [api-url api-key model provider-name]
  proto/LLMBackend

  (chat [_ messages tools]
    (let [response (chat-request api-url api-key model messages tools provider-name)
          choice (get-in response [:choices 0 :message])
          usage (:usage response)
          result (parse-response choice)]
      (log/debug (str provider-name " response parsed") {:model model :type (:type result)})
      (when (= :error (:type result))
        (log/warn (str provider-name " empty response detected") {:model model :error (:error result)}))
      (cond-> result
        usage (assoc :usage {:input (:prompt_tokens usage)
                             :output (:completion_tokens usage)
                             :total (:total_tokens usage)}))))

  (model-name [_] model))

;;; ---------------------------------------------------------------------------
;;; Provider Discovery
;;; ---------------------------------------------------------------------------

(defn available-providers
  "Return a seq of provider keywords that have API keys configured."
  []
  (filter (fn [p]
            (let [{:keys [secret-key]} (get provider-registry p)]
              (or (nil? secret-key) ;; ollama-compat needs no key
                  (some? (global-config/get-secret secret-key)))))
          provider-priority))

(defn best-available-provider
  "Return the highest-priority provider with an API key configured, or nil."
  []
  (first (available-providers)))

;;; ---------------------------------------------------------------------------
;;; Factory Functions
;;; ---------------------------------------------------------------------------

(defn openai-compat-backend
  "Create an OpenAI-compatible LLM backend.
   Options:
     :provider   - keyword from provider-registry (e.g. :openrouter, :venice, :groq)
     :api-url    - explicit URL (overrides provider registry)
     :api-key    - explicit API key (overrides secret resolution)
     :model      - model string
     :secret-key - config secret key for API key resolution

   Throws when the named provider is dispatch-routed (`:dispatch` in its
   registry entry, e.g. :anthropic) and no :api-url override is supplied —
   such a provider has no chat-completions endpoint."
  [{:keys [provider api-url api-key model secret-key]}]
  (let [reg-entry      (get provider-registry provider)
        dispatch       (:dispatch reg-entry)
        effective-url  (or api-url (:api-url reg-entry))
        effective-sk   (or secret-key (:secret-key reg-entry))
        effective-key  (or api-key
                           (when effective-sk (global-config/get-secret effective-sk)))
        effective-model (or model (:default-model reg-entry) "anthropic/claude-3-haiku")
        prov-name      (or (some-> provider name) "custom")]
    (when (and dispatch (not api-url))
      (throw (ex-info (str prov-name " is not an OpenAI-compat provider (dispatch: "
                          (name dispatch) ")")
                      {:provider provider :dispatch dispatch
                       :fix "Route through the dispatch-specific client, or pass an explicit :api-url"})))
    (when-not effective-url
      (throw (ex-info "API URL required for custom provider"
                      {:provider provider})))
    (when (and (not effective-key) (not= provider :ollama-compat))
      (throw (ex-info (str prov-name " API key required")
                      {:provider provider :secret-key effective-sk
                       :env (when effective-sk
                              (-> (name effective-sk) (str/replace "-" "_") str/upper-case))})))
    (->OpenAICompatBackend effective-url (or effective-key "") effective-model prov-name)))

(defn auto-backend
  "Create a backend using the best available provider.
   Falls back through provider-priority until one has a valid key."
  [opts]
  (if-let [provider (best-available-provider)]
    (do
      (log/info "Auto-selected provider" {:provider provider})
      (openai-compat-backend (assoc opts :provider provider)))
    (throw (ex-info "No OpenAI-compatible provider configured. Set at least one API key."
                    {:checked (mapv (fn [p] {:provider p
                                             :secret-key (:secret-key (get provider-registry p))})
                                   provider-priority)}))))

(defn openrouter-backend
  "Create an OpenRouter backend. Backward-compatible factory."
  [{:keys [api-key model] :or {model "anthropic/claude-3-haiku"}}]
  (openai-compat-backend {:provider :openrouter :api-key api-key :model model}))
