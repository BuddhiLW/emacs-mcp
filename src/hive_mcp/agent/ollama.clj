(ns hive-mcp.agent.ollama
  "Ollama backend for local LLM access via HTTP API."
  (:require [hive-mcp.agent.protocol :as proto]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.embeddings.http-client :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private http-client
  (http/mk-client
   (fn []
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 30))
         (.build)))))

(defn- http-post
  "Make HTTP POST request, return parsed JSON response.

   Uses the self-healing HttpClient cache — a fatal selector/shutdown
   error triggers one atomic rebuild + retry before propagating."
  [url body & {:keys [timeout-secs] :or {timeout-secs 300}}]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.timeout (Duration/ofSeconds timeout-secs))
                    (.build))
        response (http/send-with-retry http-client request
                                       (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (if (= status 200)
      (json/read-str body-str :key-fn keyword)
      (throw (ex-info "HTTP request failed"
                      {:status status :body body-str :url url})))))

(defn- tools->openai-format
  "Convert tool schemas to OpenAI function format for Ollama."
  [tools]
  (mapv (fn [{:keys [name description inputSchema]}]
          {:type "function"
           :function {:name name
                      :description description
                      :parameters (or inputSchema {:type "object" :properties {}})}})
        tools))

(defn- parse-response
  "Parse Ollama chat response into normalized format."
  [response]
  (let [message (get-in response [:message])
        content (:content message)
        tool-calls (:tool_calls message)]
    (cond
      (seq tool-calls)
      {:type :tool_calls
       :calls (mapv (fn [tc]
                      {:id (str (java.util.UUID/randomUUID))
                       :name (get-in tc [:function :name])
                       :arguments (let [args (get-in tc [:function :arguments])]
                                    (if (string? args)
                                      (json/read-str args :key-fn keyword)
                                      args))})
                    tool-calls)}

      content
      {:type :text :content content}

      :else
      {:type :text :content ""})))

(defrecord OllamaBackend [host model]
  proto/LLMBackend
  (chat [_ messages tools]
    (log/debug "Ollama chat request" {:model model :msg-count (count messages)})
    (let [ollama-tools (when (seq tools)
                         (tools->openai-format tools))
          body (cond-> {:model model
                        :messages messages
                        :stream false}
                 (seq ollama-tools) (assoc :tools ollama-tools))
          response (http-post (str host "/api/chat") body :timeout-secs 300)]
      (parse-response response)))

  (model-name [_] model))
