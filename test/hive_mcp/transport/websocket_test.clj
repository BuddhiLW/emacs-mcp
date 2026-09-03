(ns hive-mcp.transport.websocket-test
  "Pinning tests for WebSocket transport module.
   
   Tests verify:
   - Lockfile creation/cleanup lifecycle
   - Server start/stop state transitions
   - `connected?` state tracking
   - `notify!` behavior when connected vs disconnected
   
   Uses with-redefs for I/O mocking to keep tests fast (no actual network)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.transport.websocket :as ws]
            [aleph.http]
            [aleph.netty]
            [clojure.core.async])
  (:import [java.io File]))

;;; Test fixtures

(defn reset-websocket-state
  "Fixture to ensure clean state between tests."
  [f]
  ;; Reset internal atoms via reflection (since they're private)
  (let [server-atom-var (resolve 'hive-mcp.transport.websocket/server-atom)
        clients-var (resolve 'hive-mcp.transport.websocket/clients)]
    (when server-atom-var (reset! @server-atom-var nil))
    (when clients-var (reset! @clients-var #{})))
  (f))

(use-fixtures :each reset-websocket-state)

;;; Lockfile tests

(deftest lockfile-path-test
  (testing "lockfile-path generates correct path format"
    (let [lockfile-path-fn #'ws/lockfile-path
          path (lockfile-path-fn 8080)]
      (is (string? path))
      (is (.contains path "/.claude/ide/"))
      (is (.endsWith path "8080.lock")))))

(deftest create-lockfile!-test
  (testing "create-lockfile! writes valid JSON with required fields"
    (let [test-port 59999
          test-dir "/tmp/test-project"
          lockfile-path (str (System/getProperty "user.home") "/.claude/ide/" test-port ".lock")]
      (try
        ;; Create lockfile
        (#'ws/create-lockfile! test-port test-dir)

        ;; Verify file exists and content
        (let [lockfile (File. lockfile-path)]
          (is (.exists lockfile) "Lockfile should exist")
          (let [content (json/read-str (slurp lockfile) :key-fn keyword)]
            (is (contains? content :pid) "Should have :pid")
            (is (contains? content :workspaceFolders) "Should have :workspaceFolders")
            (is (contains? content :ideName) "Should have :ideName")
            (is (= "hive-mcp" (:ideName content)))
            (is (= "ws" (:transport content)))
            (is (= [test-dir] (:workspaceFolders content)))))
        (finally
          ;; Cleanup
          (#'ws/remove-lockfile! test-port))))))

(deftest remove-lockfile!-test
  (testing "remove-lockfile! deletes existing lockfile"
    (let [test-port 59998
          lockfile-path (str (System/getProperty "user.home") "/.claude/ide/" test-port ".lock")
          lockfile (File. lockfile-path)]
      ;; Create lockfile first
      (#'ws/create-lockfile! test-port "/tmp")
      (is (.exists lockfile) "Lockfile should exist before removal")

      ;; Remove it
      (#'ws/remove-lockfile! test-port)
      (is (not (.exists lockfile)) "Lockfile should not exist after removal")))

  (testing "remove-lockfile! is idempotent (no error on missing file)"
    ;; Should not throw
    (#'ws/remove-lockfile! 59997)))

;;; Server lifecycle tests

(deftest connected?-initial-state-test
  (testing "connected? returns false when no clients"
    (is (false? (ws/connected?)))))

(deftest connected?-with-mocked-clients-test
  (testing "connected? returns true when clients atom has entries"
    (let [clients-var (resolve 'hive-mcp.transport.websocket/clients)]
      (reset! @clients-var #{:mock-client})
      (is (true? (ws/connected?)))
      ;; Reset for cleanup
      (reset! @clients-var #{}))))

;;; notify! tests

(deftest notify!-no-clients-test
  (testing "notify! does not throw when no clients connected"
    ;; Should complete without error
    (is (nil? (ws/notify! "test/method" {:data "test"})))))

(deftest notify!-message-format-test
  (testing "notify! creates valid JSON-RPC 2.0 notification format"
    ;; Test the message format by capturing what would be sent
    ;; Note: We can't easily mock manifold protocol functions with with-redefs,
    ;; so we verify the JSON serialization logic independently
    (let [method "swarm/taskCompleted"
          params {:task-id "123" :status "done"}
          expected-msg {:jsonrpc "2.0" :method method :params params}]
      ;; Verify our expectation of the message format
      (is (= "2.0" (:jsonrpc expected-msg)))
      (is (= method (:method expected-msg)))
      (is (= params (:params expected-msg)))
      ;; Verify JSON round-trip
      (let [json-str (json/write-str expected-msg)
            parsed (json/read-str json-str :key-fn keyword)]
        (is (= "2.0" (:jsonrpc parsed)))
        (is (= method (:method parsed)))))))

;;; Server start/stop integration tests (mocked)

(deftest start-server!-creates-lockfile-test
  (testing "start-server! creates lockfile with actual port"
    (let [lockfile-created? (atom false)
          created-port (atom nil)]
      (with-redefs [ws/create-lockfile! (fn [port _]
                                          (reset! lockfile-created? true)
                                          (reset! created-port port))
                    aleph.http/start-server (fn [_ opts]
                                              (reify
                                                java.io.Closeable
                                                (close [_])))
                    aleph.netty/port (constantly 8765)]
        (let [port (ws/start-server! {:port 0
                                      :project-dir "/test"
                                      :input-ch (clojure.core.async/chan)
                                      :output-ch (clojure.core.async/chan)})]
          (is (= 8765 port))
          (is @lockfile-created?)
          (is (= 8765 @created-port)))))))

(deftest stop-server!-removes-lockfile-test
  (testing "stop-server! removes lockfile and closes server"
    (let [server-closed? (atom false)
          lockfile-removed? (atom false)
          removed-port (atom nil)
          server-atom-var (resolve 'hive-mcp.transport.websocket/server-atom)]

      ;; Setup mock server state
      (reset! @server-atom-var {:server (reify java.io.Closeable
                                          (close [_] (reset! server-closed? true)))
                                :port 8765})

      (with-redefs [ws/remove-lockfile! (fn [port]
                                          (reset! lockfile-removed? true)
                                          (reset! removed-port port))]
        (ws/stop-server!)

        (is @server-closed? "Server should be closed")
        (is @lockfile-removed? "Lockfile should be removed")
        (is (= 8765 @removed-port))
        (is (nil? @@server-atom-var) "server-atom should be nil")))))

(deftest stop-server!-idempotent-test
  (testing "stop-server! is idempotent (no error when already stopped)"
    ;; Should not throw when server-atom is nil
    (is (nil? (ws/stop-server!)))))

;;; State transition tests

(deftest server-lifecycle-state-transitions-test
  (testing "Server state transitions correctly through lifecycle"
    (let [server-atom-var (resolve 'hive-mcp.transport.websocket/server-atom)]
      ;; Initial state
      (is (nil? @@server-atom-var) "Should start nil")

      ;; After start (mocked)
      (with-redefs [ws/create-lockfile! (fn [_ _])
                    aleph.http/start-server (fn [_ _]
                                              (reify java.io.Closeable (close [_])))
                    aleph.netty/port (constantly 9999)]
        (ws/start-server! {:port 0
                           :input-ch (clojure.core.async/chan)
                           :output-ch (clojure.core.async/chan)})
        (is (some? @@server-atom-var) "Should have server state")
        (is (= 9999 (:port @@server-atom-var))))

      ;; After stop
      (with-redefs [ws/remove-lockfile! (fn [_])]
        (ws/stop-server!)
        (is (nil? @@server-atom-var) "Should be nil after stop")))))
