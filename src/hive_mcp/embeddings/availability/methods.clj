(ns hive-mcp.embeddings.availability.methods
  (:require [hive-mcp.config.core :as global-config]
            [hive-mcp.embeddings.availability.multi :as multi]))

(defmethod multi/secret-available? :ollama
  [spec]
  true)

(defmethod multi/secret-available? :openrouter
  [spec]
  (some? (global-config/get-secret :openrouter-api-key)))

(defmethod multi/secret-available? :openai
  [spec]
  (some? (global-config/get-secret :openai-api-key)))

(defmethod multi/secret-available? :venice
  [spec]
  (some? (global-config/get-secret :venice-api-key)))
