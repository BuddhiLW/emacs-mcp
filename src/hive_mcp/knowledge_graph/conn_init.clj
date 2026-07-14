(ns hive-mcp.knowledge-graph.conn-init
  "Re-exports the single-init SPI (hive-spi.kg.conn-init)."
  (:require [hive-spi.kg.conn-init :as ci]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def IConnInit     ci/IConnInit)
(def open-once!    ci/open-once!)
(def snapshot      ci/snapshot)
(def clear!        ci/clear!)
(def atom-conn-init ci/atom-conn-init)
