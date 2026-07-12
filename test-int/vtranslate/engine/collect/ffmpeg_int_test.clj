(ns vtranslate.engine.collect.ffmpeg-int-test
  "OPT-IN native integration suite (run: clojure -M:test:itest:ffmpeg).
   Loads bytedeco; excluded from the default unit run (test-int/ is off the
   default classpath). Reuses dev/ helpers. Corpus checks resolve via $VT_CORPUS
   and skip when absent; the hermetic round-trip always runs."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.dev :as dev]
            [vtranslate.engine.port.media :as pm]))

(deftest ^:ffmpeg javacv-silent-wav-round-trip
  (let [{:keys [probe extract wav-bytes]} (dev/smoke!)]
    (is (r/ok? probe))
    (let [pi (:ok probe)]
      (is (= "wav" (:container pi)))
      (is (= 1000 (:duration-ms pi)))
      (is (:has-audio? pi))
      (is (= "pcm_s16le" (:audio-codec pi))))
    (is (r/ok? extract))
    (is (pos? wav-bytes))))

(deftest ^:ffmpeg collect-media-port-remaps-errors-to-domain
  (let [port (dev/throwing-port)]
    (is (= :error/source-unreadable    (:error (pm/probe port "/no/such.mp4"))))
    (is (= :error/probe-failed         (:error (pm/probe port "/tmp"))))
    (is (= :error/audio-extract-failed (:error (pm/extract-audio port "/tmp" {}))))))

(deftest ^:ffmpeg javacv-probes-corpus-speech
  (if-let [pt (dev/corpus-file "speech/pt.mp3")]
    (let [res (pm/probe (dev/ffmpeg-port) pt)]
      (is (r/ok? res))
      (is (pos? (:duration-ms (:ok res))))
      (is (:has-audio? (:ok res))))
    (testing "corpus absent — skipped (set $VT_CORPUS to enable)"
      (is true))))
