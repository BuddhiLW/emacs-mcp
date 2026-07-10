(ns hive-mcp.extensions.delegate
  "Shared extension-delegation helper.

   Single home for the `delegate-or-noop` pattern that was duplicated verbatim
   across the delegation-stub namespaces: call the extension registered under a
   key when present, otherwise log at debug and return a default."
  (:require [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn delegate-or-noop
  "Delegate to the extension registered under `ext-key`, applying it to `args`.
   Returns the extension's result when present; otherwise logs at debug and
   returns `default-val`."
  [ext-key default-val args]
  (if-let [f (ext/get-extension ext-key)]
    (apply f args)
    (do
      (log/debug "Extension not available, returning default for" ext-key)
      default-val)))
