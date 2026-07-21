(ns hive-mcp.tools.consolidated.addon-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-mcp.addons.doctor :as doctor]
            [hive-mcp.tools.consolidated.addon :as tool]
            [hive-mcp.tools.consolidated.roster :as roster]))

(deftest tool-contract
  (is (= "addon" (:name tool/tool-def)))
  (is (:consolidated tool/tool-def))
  (is (= ["command"] (get-in tool/tool-def [:inputSchema :required])))
  (is (= ["doctor" "help"]
         (get-in tool/tool-def
                 [:inputSchema :properties "command" :enum])))
  (is (= [tool/tool-def] tool/tools))
  (is (contains? tool/handlers :doctor)))

(deftest doctor-handler-normalizes-json-style-parameters
  (let [seen (atom nil)]
    (with-redefs [doctor/run-doctor
                  (fn [input]
                    (reset! seen input)
                    (r/ok {:received true}))]
      (let [response (tool/handle-doctor
                      {:addon_id "hive.emacs"
                       :directory "/work/hive-emacs"
                       :emacs_features ["hive-mcp"]
                       :timeout_ms 900})
            body (json/read-str (:text response) :key-fn keyword)]
        (is (not (:isError response)))
        (is (= {:addon-id "hive.emacs"
                :directory "/work/hive-emacs"
                :emacs-features ["hive-mcp"]
                :timeout-ms 900}
               @seen))
        (is (= {:received true} body))))))

(deftest help-and-roster-wiring
  (testing "CLI help exposes doctor"
    (let [response (tool/handle-addon {:command "help"})]
      (is (not (:isError response)))
      (is (re-find #"doctor" (:text response)))))
  (testing "one roster row makes addon reachable from consolidated multi"
    (is (some #(= ["addon"
                   'hive-mcp.tools.consolidated.addon/handle-addon]
                  %)
              roster/consolidated-tools))
    (is (some #{"addon"} (roster/tool-names)))))

