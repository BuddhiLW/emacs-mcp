(ns hive-mcp.agent.drone.error-summary
  "Pure summarizer for drone error payloads.

   Produces a bounded, structured map safe to ship through hivemind shouts and
   piggyback blocks. Guards against raw exception dumps (stack trace + cause
   chain) bloating the coordinator's context.

   Shape:
     {:class       String        ;; fully qualified throwable/class name
      :error-type  keyword?      ;; optional — from ex-data :error/type
      :message     String        ;; truncated at :max-message (default 512)
      :frames      [Frame ...]   ;; first :max-frames (default 5) stack frames
      :cause       Summary?}     ;; optional nested cause (walks Throwable +
                                 ;; ex-info :cause), bounded by :max-cause-depth

   Frame:
     {:class  String
      :method String
      :file   String?
      :line   long}

   All rendering is pure; callers choose how to ship the summary (log, shout,
   metric). `summary->line` provides a single-line renderer honoring a budget.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-max-message 512)
(def ^:const default-max-frames 5)
(def ^:const default-max-cause-depth 3)
(def ^:const default-budget 4096)
(def ^:const unknown-class "nil")
(def ^:const unknown-message "unknown error")

(defn truncate
  "Truncate s to n chars, appending ellipsis if trimmed. Always returns String."
  [^long n s]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 n) "…"))))

(defn- ste->frame
  [^StackTraceElement ste]
  {:class  (.getClassName ste)
   :method (.getMethodName ste)
   :file   (.getFileName ste)
   :line   (.getLineNumber ste)})

(defn- throwable->frames
  [^Throwable t ^long limit]
  (into [] (comp (take limit) (map ste->frame))
        (.getStackTrace t)))

(defn- throwable-message
  [^Throwable t]
  (or (ex-message t) (.getName (class t))))

(defn- cause-of
  "Walk either Java getCause or ex-info :cause — whichever is set."
  [^Throwable t]
  (let [java-cause (.getCause t)
        data-cause (when (instance? clojure.lang.IExceptionInfo t)
                     (:cause (ex-data t)))]
    (or java-cause
        (when (instance? Throwable data-cause) data-cause))))

(declare summarize-error)

(defn- summarize-throwable
  [^Throwable t {:keys [max-message max-frames max-cause-depth] :as opts}]
  (let [msg         (throwable-message t)
        data        (when (instance? clojure.lang.IExceptionInfo t) (ex-data t))
        etype       (:error/type data)
        class-name  (.getName (class t))
        frames      (throwable->frames t max-frames)
        base        (cond-> {:class   class-name
                             :message (truncate max-message msg)
                             :frames  frames}
                      etype (assoc :error-type etype))
        cause       (cause-of t)]
    (if (and cause (pos? max-cause-depth))
      (assoc base :cause
             (summarize-throwable cause (update opts :max-cause-depth dec)))
      base)))

(defn- summarize-string
  [s {:keys [max-message]}]
  {:class   "java.lang.String"
   :message (truncate max-message s)
   :frames  []})

(defn- summarize-map
  [m {:keys [max-message]}]
  (let [etype  (:error/type m)
        msg    (or (:message m) (:error/message m))
        picked (cond
                 (and etype msg) (str etype " " msg)
                 etype           (str etype)
                 msg             msg
                 :else           (pr-str (select-keys m [:error/type :message
                                                         :error/message :type])))]
    (cond-> {:class   "clojure.lang.IPersistentMap"
             :message (truncate max-message (str picked))
             :frames  []}
      etype (assoc :error-type etype))))

(defn- summarize-coll
  [c {:keys [max-message]}]
  (let [n    (count c)
        head (truncate max-message (pr-str (first c)))]
    {:class   (.getName (class c))
     :message (truncate max-message
                        (str "<" n " items, first: " head ">"))
     :frames  []}))

(defn- summarize-fallback
  [x {:keys [max-message]}]
  {:class   (if (nil? x) unknown-class (.getName (class x)))
   :message (truncate max-message (pr-str x))
   :frames  []})

(defn summarize-error
  "Summarize any error value into a bounded, structured summary map.

   opts (all optional):
     :max-message     (default 512)  — per-message char cap
     :max-frames      (default 5)    — per-level stack-frame cap
     :max-cause-depth (default 3)    — how deep to walk cause chain"
  ([err] (summarize-error err nil))
  ([err opts]
   (let [opts (merge {:max-message     default-max-message
                      :max-frames      default-max-frames
                      :max-cause-depth default-max-cause-depth}
                     opts)]
     (cond
       (nil? err)                  {:class unknown-class
                                    :message unknown-message
                                    :frames []}
       (instance? Throwable err)   (summarize-throwable err opts)
       (string? err)               (summarize-string err opts)
       (map? err)                  (summarize-map err opts)
       (and (coll? err)
            (not (map? err)))      (summarize-coll err opts)
       :else                       (summarize-fallback err opts)))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn summary->line
  "Compact single-line renderer for a summary.

   Order: <tag>: <message>[ (N frames)][ ← cause-tag: cause-msg]
   where tag = :error-type when present, else :class.

   Honors :budget (default 512). Progressively trims until it fits:
     1. drop frame-count suffix
     2. drop cause suffix
     3. truncate remaining line."
  ([summary] (summary->line summary nil))
  ([{:keys [class error-type message frames cause]}
    {:keys [budget] :or {budget default-max-message}}]
   (let [tag        (if error-type (str error-type) class)
         head       (str tag ": " message)
         fsfx       (when (seq frames)
                      (str " (" (count frames) " frames)"))
         csfx       (when cause
                      (str " ← "
                           (or (some-> cause :error-type str) (:class cause))
                           ": " (:message cause)))
         candidates [(str head fsfx csfx)
                     (str head csfx)
                     (str head fsfx)
                     head]
         fit        (some (fn [c] (when (<= (count c) budget) c)) candidates)]
     (or fit (truncate budget head)))))

(defn- serialized-size
  [s]
  (count (pr-str s)))

(defn fit-to-budget
  "Progressively trim a summary until (count (pr-str summary)) <= budget.

   Order:
     1. halve frames
     2. drop frames
     3. drop cause
     4. drop frames + cause
     5. minimal (truncate message to fit)"
  [summary budget]
  (let [try-fit   (fn [s] (when (<= (serialized-size s) budget) s))
        halved    (update summary :frames #(vec (take (quot (count %) 2) %)))
        no-frames (assoc summary :frames [])
        no-cause  (dissoc summary :cause)
        bare      (-> summary (assoc :frames []) (dissoc :cause))
        minimal   {:class   (:class summary)
                   :message (truncate (max 64 (- budget 128))
                                      (:message summary))
                   :frames  []}]
    (or (try-fit summary)
        (try-fit halved)
        (try-fit no-frames)
        (try-fit no-cause)
        (try-fit bare)
        (try-fit minimal)
        minimal)))
