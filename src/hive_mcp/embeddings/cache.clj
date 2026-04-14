(ns hive-mcp.embeddings.cache
  "In-process LRU+TTL cache for embedding vectors.

   Keyed by [collection-name text-hash] → {:vec [...] :expires-at ms}.
   Backed by java.util.LinkedHashMap in access-order. Synchronized
   via locking — embedding calls are already slow (100ms+), so the
   coarse lock is free.

   Eviction: LRU when size exceeds cap, lazy TTL check on lookup."
  (:import [java.util LinkedHashMap Map$Entry]))

(def ^:private default-cap 2048)
(def ^:private default-ttl-ms (* 5 60 1000))

(defn- make-lru
  ^LinkedHashMap [cap]
  (proxy [LinkedHashMap] [16 0.75 true]
    (removeEldestEntry [_entry]
      (> (.size ^LinkedHashMap this) cap))))

(defonce ^:private state
  (atom {:map      (make-lru default-cap)
         :cap      default-cap
         :ttl-ms   default-ttl-ms
         :hits     0
         :misses   0
         :evicts   0}))

(defn- text-hash
  [^String text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" %) bs))))

(defn- cache-key
  [collection-name text]
  (str collection-name ":" (text-hash text)))

(defn lookup
  "Return cached embedding vector or nil. Lazy-evicts expired entries."
  [collection-name text]
  (let [k (cache-key collection-name text)
        {:keys [^LinkedHashMap map ttl-ms]} @state]
    (locking map
      (if-let [entry (.get map k)]
        (if (< (System/currentTimeMillis) (:expires-at entry))
          (do (swap! state update :hits inc)
              (:vec entry))
          (do (.remove map k)
              (swap! state (fn [s] (-> s (update :evicts inc) (update :misses inc))))
              nil))
        (do (swap! state update :misses inc)
            nil)))))

(defn store!
  "Insert a fresh embedding into the cache."
  [collection-name text embedding]
  (let [k (cache-key collection-name text)
        {:keys [^LinkedHashMap map ttl-ms]} @state
        expires (+ (System/currentTimeMillis) ttl-ms)]
    (locking map
      (.put map k {:vec embedding :expires-at expires}))
    embedding))

(defn stats
  []
  (let [{:keys [^LinkedHashMap map hits misses evicts cap ttl-ms]} @state
        total (+ hits misses)]
    {:size    (.size map)
     :cap     cap
     :ttl-ms  ttl-ms
     :hits    hits
     :misses  misses
     :evicts  evicts
     :hit-ratio (if (pos? total) (double (/ hits total)) 0.0)}))

(defn clear!
  []
  (let [^LinkedHashMap m (:map @state)]
    (locking m (.clear m))
    (swap! state assoc :hits 0 :misses 0 :evicts 0)))

(defn configure!
  "Rebuild cache with new cap/ttl. Clears existing entries."
  [{:keys [cap ttl-ms]}]
  (let [cap' (or cap default-cap)
        ttl' (or ttl-ms default-ttl-ms)]
    (swap! state assoc
           :map (make-lru cap')
           :cap cap'
           :ttl-ms ttl'
           :hits 0 :misses 0 :evicts 0)))
