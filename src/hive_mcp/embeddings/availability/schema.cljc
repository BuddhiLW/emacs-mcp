(ns hive-mcp.embeddings.availability.schema
  (:require [hive-spi.schema.registry :as reg]))

(reg/register-all!
 {:hive-mcp.embeddings/impl :keyword})
