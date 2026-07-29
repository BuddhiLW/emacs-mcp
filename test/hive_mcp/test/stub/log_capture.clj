(ns hive-mcp.test.stub.log-capture
  "Capture timbre log events in tests.

   timbre's log/info, log/warn, log/error are MACROS: `with-redefs` on them
   binds a var the expanded call site never consults, so a test that mocks
   them captures nothing and silently asserts against an empty vector.
   Appenders are timbre's real injection seam — this ns installs one.

   Each captured event: {:level :vargs :exception}
     :vargs     — the arguments as logged, e.g. [:timing {:operation \"x\"}]
     :exception — timbre's :?err"
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn capture-appender
  "A synchronous timbre appender that conj's every event onto SINK."
  [sink]
  {:enabled?  true
   :async?    false
   :min-level nil
   :rate-limit nil
   :output-fn :inherit
   :fn        (fn [data]
                (swap! sink conj {:level     (:level data)
                                  :vargs     (vec (force (:vargs data)))
                                  :exception (:?err data)}))})

(defmacro with-captured-logs
  "Bind SINK-SYM to an atom collecting every timbre event raised in BODY.

   Other appenders are muted for the duration so the test run stays quiet."
  [sink-sym & body]
  `(let [~sink-sym (atom [])]
     (log/with-merged-config
       {:min-level :trace
        :appenders {:println      {:enabled? false}
                    :test-capture (capture-appender ~sink-sym)}}
       ~@body)))

(defn ->event
  "Structured view of one captured event: the hive convention is
   (log/info :some-event {..data..} & extras)."
  [e]
  {:level     (:level e)
   :event     (first (:vargs e))
   :data      (second (:vargs e))
   :vargs     (:vargs e)
   :exception (or (:exception e) (last (:vargs e)))})

(defn events
  "All captured events in structured form, oldest first."
  [sink]
  (mapv ->event @sink))

(defn events-of
  "Captured events whose first varg is EVENT-KW."
  [sink event-kw]
  (filterv #(= event-kw (:event %)) (events sink)))

(defn event-data
  "The data map carried by the first EVENT-KW event."
  [sink event-kw]
  (:data (first (events-of sink event-kw))))
