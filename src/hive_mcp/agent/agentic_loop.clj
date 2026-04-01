(ns hive-mcp.agent.agentic-loop
  "IAgenticLoop — protocol for in-process agentic loops.

   Vanilla version lives here in hive-mcp (AGPL). Enhanced implementations
   with coordinator mode, fork, KG-compression etc. live in hive-agent (proprietary).

   Two control gradients:
   - TRANSPARENT: tool calls brokered in-process, caller sees every execution
   - OPAQUE: tool calls hidden behind API boundary (e.g. Claude Code SDK)

   Lifecycle:
     :idle → start! → :running → [abort!|complete] → :done/:aborted/:errored

   Implementors:
   - hive-agent.loop.agentic/TransparentAgenticLoop (in-process OpenRouter loop)
   - hive-claude.sdk.agentic-loop/ClaudeSDKAgenticLoop (Claude Code SDK wrapper)

   See also:
   - hive-mcp.addons.headless/IHeadlessBackend — headless backend protocol
   - hive-agent.loop.headless-adapter — IAgenticLoop→IHeadlessBackend bridge")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IAgenticLoop Protocol
;; =============================================================================

(defprotocol IAgenticLoop
  "Protocol for in-process agentic loops.

   Provides rich control over multi-turn LLM interactions with tool execution:
   - Async start/abort lifecycle
   - Collect result with timeout
   - Real-time cost tracking
   - Full transcript access
   - Mid-session constraint injection (budget, max-turns)
   - Message injection between turns"

  (start! [this start-config]
    "Start the agentic loop with the given configuration.
     Merges start-config with base config from construction.
     Returns: {:session-id str}
     Side effects: transitions session-state from :idle to :running.")

  (abort! [this]
    "Abort the running loop gracefully.
     Returns: {:aborted? boolean}
     Side effects: transitions session-state to :aborted.")

  (session-state [this]
    "Return current session state keyword.
     One of: :idle, :running, :done, :errored, :aborted")

  (send-message! [this message]
    "Inject a message into the running loop between turns.
     The message is queued and consumed at the next turn boundary.
     Returns: {:sent? boolean}")

  (collect-response! [this opts]
    "Block until the loop completes or timeout.
     opts: {:timeout-ms long}
     Returns: result map on completion, {:timeout true} on timeout.
     Result map: {:result str, :turns int, :tool-calls-made int, ...}")

  (cost [this]
    "Return cost tracking information.
     Returns: {:total-cost-usd double, :turns int}")

  (transcript [this]
    "Return the full message transcript as a vector of message maps.
     Each entry is {:role str, :content str, ...}")

  (tool-results! [this results]
    "Inject external tool results into the loop.
     For transparent loops: appended to transcript (tools already handled internally).
     For opaque loops: forwarded to the underlying runtime.
     Returns: {:accepted? boolean} or {:unsupported true}")

  (hooks [this]
    "Return set of capability/hook keywords this loop supports.
     Standard caps: :cap/transparent, :cap/opaque
     Optional: :pre-tool-use, :post-tool-use, :cap/streaming,
               :cap/multi-turn, :cap/cost-tracking, :cap/transcript,
               :cap/constraints, :cap/coordinator")

  (constrain! [this new-constraints]
    "Apply runtime constraints to the running loop.
     new-constraints: {:max-turns int, :max-cost-usd double}
     Returns: {:applied? boolean}"))

;; =============================================================================
;; ISidechainTranscript Protocol
;; =============================================================================

(defprotocol ISidechainTranscript
  "Protocol for persistent sidechain transcript recording.
   Each agent session gets its own JSONL transcript file,
   enabling replay, debugging, and fork-from-transcript."

  (transcript-path [this]
    "Return the filesystem path to this agent's JSONL transcript file.
     Returns: string path, or nil if not persisted.")

  (flush-transcript! [this]
    "Force-flush any buffered transcript entries to disk.
     Returns: {:flushed? boolean, :entries int}")

  (transcript-since [this cursor]
    "Return transcript entries after the given cursor.
     cursor: {:turn int} or {:timestamp long}
     Returns: vector of message maps since cursor."))

;; =============================================================================
;; Predicates
;; =============================================================================

(defn agentic-loop?
  "Check if x satisfies IAgenticLoop."
  [x]
  (satisfies? IAgenticLoop x))

(defn transparent?
  "Check if the loop operates in transparent mode (tool calls visible)."
  [x]
  (and (agentic-loop? x)
       (contains? (hooks x) :cap/transparent)))

(defn opaque?
  "Check if the loop operates in opaque mode (tool calls hidden behind API)."
  [x]
  (and (agentic-loop? x)
       (contains? (hooks x) :cap/opaque)))

(defn coordinator?
  "Check if the loop is running in coordinator mode."
  [x]
  (and (agentic-loop? x)
       (contains? (hooks x) :cap/coordinator)))

(defn has-transcript?
  "Check if the loop supports sidechain transcript persistence."
  [x]
  (satisfies? ISidechainTranscript x))
