(ns hive-mcp.experiments.embedding-retrieval
  "Read-only Qwen3 embedding retrieval experiment.

   Compares query shaping (raw vs upstream-recommended instruction) and document
   shaping (Milvus content-only vs Chroma metadata envelope) over a fixed corpus.
   No vector store is read or mutated; Ollama is the only external dependency.

   Run:
     clojure -X:embedding-retrieval
     clojure -X:embedding-retrieval :model \"qwen3-embedding:8b\"
     clojure -X:embedding-retrieval :fixture \"dev/experiments/private/case.private.edn\""
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration]))

(def fixture-resource
  "hive_mcp/experiments/fixtures/stratified_design.example.edn")

(def qwen-retrieval-instruction
  "Given a memory search query, retrieve stored memory passages relevant to the query")

(def thresholds [0.40 0.45 0.50 0.55 0.60])

(defn sha256
  "Stable lowercase SHA-256 for experiment identity."
  [x]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str x) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn magnitude [xs]
  (Math/sqrt (reduce (fn [acc x] (+ acc (* (double x) (double x)))) 0.0 xs)))

(defn cosine
  "Cosine similarity. Works whether provider normalizes vectors or not."
  [a b]
  (let [denom (* (magnitude a) (magnitude b))]
    (if (zero? denom)
      0.0
      (/ (reduce + (map #(* (double %1) (double %2)) a b)) denom))))

(defn document-text
  "Mirror two live write lanes: Milvus content-only and Chroma envelope."
  [mode {:keys [type tags content]}]
  (case mode
    :content-only content
    :metadata-envelope
    (str "Type: " (or type "note") "\n"
         (when (seq tags) (str "Tags: " (str/join ", " tags) "\n"))
         "Content: " content)))

(defn query-text
  "Raw query or Qwen upstream retrieval-instruction format."
  [mode query]
  (case mode
    :raw query
    :qwen-instruct
    (str "Instruct: " qwen-retrieval-instruction "\n Query:" query)))

(defn rank
  [query-vector entries document-vectors]
  (->> (map vector entries document-vectors)
       (map (fn [[entry vector]]
              (let [similarity (cosine query-vector vector)]
                {:id (:id entry)
                 :similarity similarity
                 :distance (max 0.0 (- 1.0 similarity))})))
       (sort-by :similarity >)
       (map-indexed #(assoc %2 :rank (inc %1)))
       vec))

(defn target-rank [target-id ranked]
  (:rank (first (filter #(= target-id (:id %)) ranked))))

(defn precision-at
  [qrels k ranked]
  (let [top (take k ranked)]
    (/ (double (count (filter #(contains? qrels (:id %)) top))) k)))

(defn reciprocal-rank [target-id ranked]
  (if-let [r (target-rank target-id ranked)] (/ 1.0 r) 0.0))

(defn threshold-counts [ranked]
  (into (sorted-map)
        (map (fn [threshold]
               [threshold (count (filter #(>= (:similarity %) threshold) ranked))]))
        thresholds))

(defn summarize
  [fixture query-mode document-mode query-vector document-vectors]
  (let [{:keys [target-id qrels entries]} fixture
        ranked (rank query-vector entries document-vectors)
        relevant? #(contains? qrels (:id %))
        top-5 (take 5 ranked)]
    {:query-mode query-mode
     :document-mode document-mode
     :target-rank (target-rank target-id ranked)
     :target-similarity (:similarity (first (filter #(= target-id (:id %)) ranked)))
     :reciprocal-rank (reciprocal-rank target-id ranked)
     :precision-at-3 (precision-at qrels 3 ranked)
     :precision-at-5 (precision-at qrels 5 ranked)
     :unrelated-in-top-5 (count (remove relevant? top-5))
     :threshold-counts (threshold-counts ranked)
     :query-vector-norm (magnitude query-vector)
     :document-vector-norms {:min (apply min (map magnitude document-vectors))
                             :max (apply max (map magnitude document-vectors))}
     :top-5 (mapv #(select-keys % [:rank :id :similarity :distance]) top-5)}))

(defn- http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- request-json
  [client method url body]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (Duration/ofMinutes 3))
                    (.header "Content-Type" "application/json"))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder
                               (HttpRequest$BodyPublishers/ofString
                                (json/write-str body))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "HTTP request failed"
                      {:url url :status (.statusCode response) :body (.body response)})))
    (json/read-str (.body response) :key-fn keyword)))

(defn- embed-batch
  [client host model texts]
  (:embeddings
   (request-json client :post (str host "/api/embed")
                 {:model model
                  :input (vec texts)
                  :truncate false
                  :keep_alive "15m"
                  :options {:num_ctx 8192}})))

(defn- model-metadata [client host model]
  (let [version (request-json client :get (str host "/api/version") nil)
        tags (:models (request-json client :get (str host "/api/tags") nil))
        model-row (first (filter #(or (= model (:name %)) (= model (:model %))) tags))]
    {:ollama-version (:version version)
     :model model
     :digest (:digest model-row)
     :size (:size model-row)
     :details (:details model-row)}))

(defn- load-fixture
  ([]
   (-> fixture-resource io/resource slurp edn/read-string))
  ([fixture-path]
   (-> fixture-path slurp edn/read-string)))

(defn- git-sha []
  (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn run!
  "Run experiment. Accepts :host, :model, optional :query and local :fixture path."
  [{:keys [host model query fixture]
    :or {host "http://localhost:11434"
         model "qwen3-embedding:4b"}}]
  (let [fixture (cond-> (if fixture (load-fixture fixture) (load-fixture))
                  query (assoc :query query))
        client (http-client)
        query-modes [:raw :qwen-instruct]
        document-modes [:content-only :metadata-envelope]
        queries (mapv #(query-text % (:query fixture)) query-modes)
        document-batches
        (into {}
              (for [document-mode document-modes]
                [document-mode
                 (embed-batch client host model
                              (map #(document-text document-mode %) (:entries fixture)))]))
        query-vectors (zipmap query-modes (embed-batch client host model queries))
        results (vec
                 (for [document-mode document-modes
                       query-mode query-modes]
                   (summarize fixture query-mode document-mode
                              (get query-vectors query-mode)
                              (get document-batches document-mode))))
        report {:experiment (:name fixture)
                :read-only true
                :runtime {:java (System/getProperty "java.version")
                          :clojure (clojure-version)
                          :git-sha (git-sha)}
                :provider (model-metadata client host model)
                :corpus {:sha256 (sha256 (pr-str fixture))
                         :entries (count (:entries fixture))
                         :query (:query fixture)
                         :target-id (:target-id fixture)}
                :embedding {:dimension (count (first (vals query-vectors)))
                            :instruction qwen-retrieval-instruction}
                :results results}]
    (pprint/pprint report)
    report))
