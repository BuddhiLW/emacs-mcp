#!/usr/bin/env bash
set -euo pipefail

# Default values
WORKSPACE=${WORKSPACE:-/workspace}
CACHE_DIR=${CACHE_DIR:-/cache}
INTERVAL=${ANALYSIS_INTERVAL_SECONDS:-300}
AUTO_DISCOVER=${LSP_AUTO_DISCOVER:-false}
WORKER_COUNT=${LSP_WORKER_COUNT:-2}
PROJECT_TIMEOUT=${LSP_PROJECT_TIMEOUT_SECONDS:-300}
LSP_JAR=/opt/clojure-lsp.jar
REQUEST_FILE="$CACHE_DIR/_request.edn"
HEALTH_FILE="$CACHE_DIR/_health.edn"
HEARTBEAT_INTERVAL=${LSP_HEARTBEAT_INTERVAL_SECONDS:-5}

[[ "$WORKER_COUNT" =~ ^[1-9][0-9]*$ ]] || WORKER_COUNT=2
[[ "$PROJECT_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || PROJECT_TIMEOUT=300
[[ "$HEARTBEAT_INTERVAL" =~ ^[1-9][0-9]*$ ]] || HEARTBEAT_INTERVAL=5

enabled() {
    case "${1,,}" in
        1|true|yes|on) return 0 ;;
        *)             return 1 ;;
    esac
}

write_edn_atomic() {
    local path=$1
    local value=$2
    local directory
    local temp
    directory=$(dirname "$path")
    mkdir -p "$directory"
    temp="${path}.tmp.${BASHPID:-$$}.${RANDOM}"
    printf '%s\n' "$value" > "$temp"
    mv "$temp" "$path"
}

read_job_field() {
    local path=$1
    local field=$2
    [ -f "$path" ] || return 1
    bb -e '
      (require (quote [clojure.edn :as edn]))
      (let [[path field] *command-line-args*
            value (get (edn/read-string (slurp path)) (keyword field))]
        (when (some? value) (print value)))' "$path" "$field"
}

new_job_id() {
    bb -e '(print (str (java.util.UUID/randomUUID)))'
}

update_job_state() {
    local path=$1
    local expected_job_id=$2
    local patch=$3
    local temp="${path}.tmp.${BASHPID:-$$}.${RANDOM}"
    if bb -e '
      (require (quote [clojure.edn :as edn]))
      (let [[path expected patch] *command-line-args*
            current (edn/read-string (slurp path))]
        (if (= expected (:job-id current))
          (print (pr-str (merge current (edn/read-string patch))))
          (System/exit 42)))' \
        "$path" "$expected_job_id" "$patch" > "$temp"; then
        mv "$temp" "$path"
    else
        local status=$?
        rm -f "$temp"
        return "$status"
    fi
}

cancel_requested() {
    local cancel_path=$1
    local expected_job_id=$2
    [ -f "$cancel_path" ] || return 1
    bb -e '
      (require (quote [clojure.edn :as edn]))
      (let [[path expected] *command-line-args*]
        (System/exit
         (if (= expected (:job-id (edn/read-string (slurp path)))) 0 1)))' \
       "$cancel_path" "$expected_job_id"
}

terminal_meta_edn() {
    bb -e '
      (let [[status project-root project-id job-id timestamp completed duration
             exit-code extracted queue-latency] *command-line-args*]
        (print
         (pr-str
          {:timestamp (parse-long timestamp)
           :completed-at-ms (parse-long completed)
           :duration-ms (parse-long duration)
           :queue-latency-ms (parse-long queue-latency)
           :project-root project-root
           :project-id project-id
           :job-id job-id
           :status (keyword status)
           :exit-code (parse-long exit-code)
           :extracted (= "true" extracted)})))' "$@"
}

finish_job() {
    local project_root=$1
    local project_id=$2
    local job_id=$3
    local status=$4
    local timestamp=$5
    local started_at_ms=$6
    local queue_latency_ms=$7
    local exit_code=$8
    local extracted=$9
    local cache_path="$CACHE_DIR/$project_id"
    local job_path="$cache_path/job.edn"
    local cancel_path="$cache_path/cancel.edn"
    local completed_at_ms
    local duration_ms
    local patch
    completed_at_ms=$(date +%s%3N)
    duration_ms=$(( completed_at_ms - started_at_ms ))
    patch="{:status :$status :completed-at-ms $completed_at_ms :duration-ms $duration_ms :queue-latency-ms $queue_latency_ms :exit-code $exit_code :extracted $extracted}"

    if update_job_state "$job_path" "$job_id" "$patch"; then
        write_edn_atomic \
            "$cache_path/meta.edn" \
            "$(terminal_meta_edn "$status" "$project_root" "$project_id" \
                "$job_id" "$timestamp" "$completed_at_ms" "$duration_ms" \
                "$exit_code" "$extracted" "$queue_latency_ms")"
        if cancel_requested "$cancel_path" "$job_id"; then
            rm -f "$cancel_path"
        fi
    else
        echo "Job $job_id for $project_id was superseded; skipping terminal state"
    fi
}

config_has_custom_project_specs() {
    local config=$1
    local allow_generated_local=${2:-false}
    [ -f "$config" ] || return 1
    bb -e '
      (require (quote [clojure.edn :as edn]))
      (let [[config allow-generated-local] *command-line-args*
            specs (:project-specs (edn/read-string (slurp config)))
            plain-specs #{{:project-path "deps.edn"
                           :classpath-cmd ["clojure" "-Spath"]}
                          {:project-path "project.clj"
                           :classpath-cmd ["lein" "classpath"]}}
            local-commands #{["clojure" "-A:local-src" "-Spath"]
                             ["clojure" "-M:local-src" "-Spath"]
                             ["clojure" "-Spath" "-M:local-src"]
                             ["clojure" "-Spath" "-A:local-src"]}
            generated-sdeps?
            (fn [{:keys [project-path classpath-cmd]}]
              (and (= "deps.edn" project-path)
                   (vector? classpath-cmd)
                   (= 4 (count classpath-cmd))
                   (= ["clojure" "-Sdeps"] (subvec classpath-cmd 0 2))
                   (= "-Spath" (peek classpath-cmd))
                   (try
                     (let [sdeps (edn/read-string (nth classpath-cmd 2))]
                       (and (= #{:deps} (set (keys sdeps)))
                            (every? (fn [[_ dep]]
                                      (and (= #{:local/root} (set (keys dep)))
                                           (string? (:local/root dep))))
                                    (:deps sdeps))))
                     (catch Exception _ false))))
            generated-local?
            (fn [{:keys [project-path classpath-cmd] :as spec}]
              (and (= "deps.edn" project-path)
                   (or (local-commands classpath-cmd)
                       (generated-sdeps? spec))))
            replaceable?
            (fn [spec]
              (or (plain-specs spec)
                  (and (= "true" allow-generated-local)
                       (generated-local? spec))))]
        (System/exit
         (if (and (seq specs) (not (every? replaceable? specs))) 0 1)))' \
       "$config" "$allow_generated_local" 2>/dev/null
}

mounted_local_src() {
    local project_root=$1
    local deps="$project_root/deps.edn"
    [ -f "$deps" ] || return 1
    bb -e '
      (require (quote [babashka.fs :as fs])
               (quote [clojure.edn :as edn]))
      (let [[workspace project-root deps-file] *command-line-args*
            workspace (fs/canonicalize workspace)
            project-root (fs/canonicalize project-root)
            alias (get-in (edn/read-string (slurp deps-file))
                          [:aliases :local-src])
            dep-maps (select-keys alias
                                  [:extra-deps :override-deps :default-deps])
            local-roots (keep :local/root
                              (mapcat vals (vals dep-maps)))
            targets (concat (:extra-paths alias)
                            (:replace-paths alias)
                            local-roots)
            target-path (fn [target]
                          (let [path (fs/path target)]
                            (if (fs/absolute? path)
                              path
                              (fs/path project-root path))))
            mounted? (fn [target]
                       (let [path (target-path target)]
                         (and (fs/exists? path)
                              (fs/starts-with? (fs/canonicalize path)
                                               workspace))))]
        (System/exit
         (if (and (map? alias)
                  (fs/starts-with? project-root workspace)
                  (every? mounted? targets))
           0
           1)))' "$WORKSPACE" "$project_root" "$deps" 2>/dev/null
}

settings_for_project() {
    local project_root=$1
    local config="$project_root/.lsp/config.edn"
    local -a specs=()
    local deps_cmd='["clojure" "-Spath"]'
    local use_local_src=false

    if [ -f "$project_root/deps.edn" ] && mounted_local_src "$project_root"; then
        use_local_src=true
    fi
    if config_has_custom_project_specs "$config" "$use_local_src"; then
        printf '{}\n'
        return 0
    fi
    if [ -f "$project_root/deps.edn" ]; then
        if [ "$use_local_src" = true ]; then
            deps_cmd='["clojure" "-A:local-src" "-Spath"]'
        fi
        specs+=("{:project-path \"deps.edn\" :classpath-cmd $deps_cmd}")
    fi
    if [ -f "$project_root/project.clj" ]; then
        specs+=("{:project-path \"project.clj\" :classpath-cmd [\"lein\" \"classpath\"]}")
    fi
    if (( ${#specs[@]} == 0 )); then
        printf '{}\n'
    else
        local joined
        joined=$(IFS=' '; printf '%s' "${specs[*]}")
        printf '{:project-specs [%s]}\n' "$joined"
    fi
}

analyze_project() {
    local project_root=$1
    local project_id=$2
    local cache_path="$CACHE_DIR/$project_id"
    mkdir -p "$cache_path"
    local job_path="$cache_path/job.edn"
    local cancel_path="$cache_path/cancel.edn"
    local start_time
    local queued_at_ms
    local started_at_ms
    local queue_latency_ms
    local job_id
    local settings
    local -a java_opts=()
    local cancel_signal=0
    local analysis_pid=""
    local exit_code=0
    local extracted=false
    local old_term old_int
    start_time=$(date +%s)
    started_at_ms=$(date +%s%3N)

    job_id=$(read_job_field "$job_path" job-id 2>/dev/null || true)
    queued_at_ms=$(read_job_field "$job_path" queued-at-ms 2>/dev/null || true)
    if [ -z "$job_id" ]; then
        job_id=$(new_job_id)
        queued_at_ms=$started_at_ms
        write_edn_atomic \
            "$job_path" \
            "{:job-id \"$job_id\" :project-id \"$project_id\" :status :queued :queued-at-ms $queued_at_ms}"
    fi
    [[ "$queued_at_ms" =~ ^[0-9]+$ ]] || queued_at_ms=$started_at_ms
    queue_latency_ms=$(( started_at_ms - queued_at_ms ))

    if cancel_requested "$cancel_path" "$job_id"; then
        echo "Job $job_id for $project_id was cancelled before start"
        finish_job "$project_root" "$project_id" "$job_id" cancelled \
            "$start_time" "$started_at_ms" "$queue_latency_ms" 143 false
        return 0
    fi

    update_job_state \
        "$job_path" "$job_id" \
        "{:status :running :started-at-ms $started_at_ms :queue-latency-ms $queue_latency_ms :worker-pid ${BASHPID:-$$}}" \
        || return 0

    old_term=$(trap -p TERM || true)
    old_int=$(trap -p INT || true)
    trap 'cancel_signal=1; if [ -n "${analysis_pid:-}" ]; then kill -TERM "$analysis_pid" 2>/dev/null || true; fi' TERM INT

    echo "Analyzing job $job_id for $project_id at $project_root (queue ${queue_latency_ms}ms)"

    # clojure-lsp dumps "Read-only file system" warnings to stdout, polluting
    # the EDN output. Pipe through grep -v to strip them. Also use a writable
    # kondo cache dir to prevent sync_cache errors on read-only mounts.
    local kondo_cache="$CACHE_DIR/kondo-cache/$project_id"
    mkdir -p "$kondo_cache"

    # Auto-generated/default project specs are safe to replace. If :local-src
    # exists and every referenced path is visible under /workspace, use it;
    # truly custom project specs remain authoritative.
    settings=$(settings_for_project "$project_root")
    if [ -n "${JAVA_OPTS:-}" ]; then
        read -r -a java_opts <<< "$JAVA_OPTS"
    fi

    timeout --signal=TERM --kill-after=15s "${PROJECT_TIMEOUT}s" \
        java "${java_opts[@]}" -jar "$LSP_JAR" dump --project-root "$project_root" \
        --output '{:format :edn :filter-keys [:analysis :dep-graph]}' \
        --analysis '{:type :project-only}' \
        --settings "$settings" \
        2>"$cache_path/dump.log" \
        > "$cache_path/dump.edn.raw" &
    analysis_pid=$!
    update_job_state "$job_path" "$job_id" "{:process-pid $analysis_pid}" || true
    if wait "$analysis_pid"; then
        exit_code=0
    else
        exit_code=$?
    fi
    analysis_pid=""

    if (( cancel_signal == 1 )) || cancel_requested "$cancel_path" "$job_id"; then
        rm -f "$cache_path/dump.edn.raw" "$cache_path/dump.edn.tmp"
        finish_job "$project_root" "$project_id" "$job_id" cancelled \
            "$start_time" "$started_at_ms" "$queue_latency_ms" 143 false
    elif (( exit_code == 0 )) && \
         grep -v 'Read-only file system' "$cache_path/dump.edn.raw" \
             > "$cache_path/dump.edn.tmp" && \
         [ -s "$cache_path/dump.edn.tmp" ]; then
        rm -f "$cache_path/dump.edn.raw"
        mv "$cache_path/dump.edn.tmp" "$cache_path/dump.edn"

        # Post-process: extract focused data files from monolithic dump.
        # This avoids 40+ min EDN parse on the host JVM side.
        echo "Extracting focused data files for $project_id..."
        if bb /usr/local/bin/extract.bb "$cache_path"; then
            extracted=true
            echo "Extraction completed for $project_id"
        else
            echo "Extraction failed for $project_id (non-fatal, dump.edn still available)"
        fi
        if (( cancel_signal == 1 )) || cancel_requested "$cancel_path" "$job_id"; then
            finish_job "$project_root" "$project_id" "$job_id" cancelled \
                "$start_time" "$started_at_ms" "$queue_latency_ms" 143 "$extracted"
        else
            finish_job "$project_root" "$project_id" "$job_id" ok \
                "$start_time" "$started_at_ms" "$queue_latency_ms" 0 "$extracted"
            echo "Analysis + extraction successful for $project_id"
        fi
    else
        (( exit_code == 0 )) && exit_code=65
        rm -f "$cache_path/dump.edn.raw" "$cache_path/dump.edn.tmp"
        finish_job "$project_root" "$project_id" "$job_id" error \
            "$start_time" "$started_at_ms" "$queue_latency_ms" "$exit_code" false
        echo "Analysis failed for $project_id with exit code $exit_code"
    fi

    if [ -n "$old_term" ]; then eval "$old_term"; else trap - TERM; fi
    if [ -n "$old_int" ]; then eval "$old_int"; else trap - INT; fi
}

discover_projects() {
    if [ -n "${LSP_PROJECTS:-}" ]; then
        IFS=',' read -ra PROJECTS <<< "$LSP_PROJECTS"
        for proj in "${PROJECTS[@]}"; do
            # Normalize: strip leading slashes and /workspace/ prefix to prevent path duplication
            proj="${proj#/}"
            proj="${proj#workspace/}"
            proj="${proj#/}"
            if [ "$proj" = "workspace" ] || [ -z "$proj" ]; then
                # Root workspace requested — use WORKSPACE directly
                echo "$WORKSPACE:workspace"
            else
                echo "$WORKSPACE/$proj:$proj"
            fi
        done
    else
        # maxdepth 3 so /workspace/<group>/<project>/{deps.edn,project.clj}
        # is found. Match both build files (sort -u collapses a project that
        # ships both into one entry). Project-id is the path relative to
        # /workspace (preserves grouping so "hive/hive-mcp" and "sec/foo"
        # don't collide on basename).
        find "$WORKSPACE" -mindepth 1 -maxdepth 3 \( -name deps.edn -o -name project.clj \) -exec dirname {} \; | sort -u | while read -r dir; do
            local rel="${dir#"$WORKSPACE"/}"
            echo "$dir:$rel"
        done
    fi
}

# Atomically move the shared inbox out of the producer's path. A producer that
# opens the old inode before the move writes into this claim; a producer that
# opens it after the move creates a fresh inbox for the next drain. This removes
# the former read-then-rm window that could delete concurrent appends.
claim_request_file() {
    [ -s "$REQUEST_FILE" ] || return 1
    local claim="${REQUEST_FILE}.processing.$$.$RANDOM"
    mv "$REQUEST_FILE" "$claim" 2>/dev/null || return 1
    printf '%s\n' "$claim"
}

# `wait PID` is interrupted by SIGHUP even though PID keeps running. Retry the
# wait while the child is alive so a later request signal cannot make the main
# loop forget an in-flight analyzer.
wait_for_worker() {
    local worker_pid=$1
    local status=0
    while kill -0 "$worker_pid" 2>/dev/null; do
        if wait "$worker_pid"; then
            return 0
        else
            status=$?
        fi
        if ! kill -0 "$worker_pid" 2>/dev/null; then
            return "$status"
        fi
    done
    wait "$worker_pid" 2>/dev/null || true
}

process_request_batch() {
    local claim=$1
    local -a worker_pids=()
    local project_id project_root

    echo "Processing dynamic request batch: $claim (workers: $WORKER_COUNT)"
    while IFS= read -r project_id; do
        project_id=$(printf '%s' "$project_id" | tr -d '[:space:]')
        [[ -z "$project_id" || "$project_id" == \#* ]] && continue
        project_root="$WORKSPACE/$project_id"
        if [ -d "$project_root" ]; then
            analyze_project "$project_root" "$project_id" &
            worker_pids+=("$!")
            if (( ${#worker_pids[@]} >= WORKER_COUNT )); then
                wait_for_worker "${worker_pids[0]}" || true
                worker_pids=("${worker_pids[@]:1}")
            fi
        else
            echo "Requested project not found: $project_root"
        fi
    done < <(sort -u "$claim")

    local worker_pid
    for worker_pid in "${worker_pids[@]}"; do
        wait_for_worker "$worker_pid" || true
    done
    rm -f "$claim"
}

# Drain every batch available. New appends made while a claimed batch runs are
# left in a fresh inbox and picked up before scheduled/idle work can start.
process_requests() {
    local processed=1
    local claim
    while claim=$(claim_request_file); do
        processed=0
        process_request_batch "$claim"
    done
    return "$processed"
}

run_scheduled_discovery() {
    local project_dir project_id
    while IFS=: read -r project_dir project_id; do
        if [ -s "$REQUEST_FILE" ]; then
            echo "Dynamic request pending; preempting scheduled discovery"
            return 0
        fi
        analyze_project "$project_dir" "$project_id"
    done < <(discover_projects)
}

write_health() {
    local status=$1
    local heartbeat_at_ms
    heartbeat_at_ms=$(date +%s%3N)
    write_edn_atomic \
        "$HEALTH_FILE" \
        "{:status :$status :heartbeat-at-ms $heartbeat_at_ms :pid $$ :worker-count $WORKER_COUNT}"
}

heartbeat_loop() {
    trap 'exit 0' TERM INT
    while true; do
        write_health ok
        sleep "$HEARTBEAT_INTERVAL"
    done
}

heartbeat_pid=""

cleanup_main() {
    if [ -n "$heartbeat_pid" ] && kill -0 "$heartbeat_pid" 2>/dev/null; then
        kill "$heartbeat_pid" 2>/dev/null || true
        wait "$heartbeat_pid" 2>/dev/null || true
    fi
    write_health down
}

echo "Starting clojure-lsp sidecar analysis with interval: ${INTERVAL}s"
echo "Workspace: $WORKSPACE"
echo "Cache directory: $CACHE_DIR"
echo "Request file: $REQUEST_FILE"
echo "LSP JAR: $LSP_JAR"
echo "Auto-discovery: $AUTO_DISCOVER"
echo "On-demand workers: $WORKER_COUNT"
echo "Per-project timeout: ${PROJECT_TIMEOUT}s"
echo "Heartbeat interval: ${HEARTBEAT_INTERVAL}s"

# Flag for immediate re-run on SIGHUP
rerun_immediately=0

main() {
    mkdir -p "$CACHE_DIR"
    write_health ok
    heartbeat_loop &
    heartbeat_pid=$!
    trap 'rerun_immediately=1' SIGHUP
    trap 'cleanup_main' EXIT
    trap 'exit 0' TERM INT

    while true; do
        if process_requests; then
            :
        elif enabled "$AUTO_DISCOVER"; then
            run_scheduled_discovery
        else
            echo "No dynamic requests; automatic workspace discovery disabled"
        fi

        if [ "$rerun_immediately" -eq 1 ]; then
            rerun_immediately=0
            echo "Re-running immediately due to SIGHUP"
        else
            echo "Sleeping for ${INTERVAL}s"
            sleep "$INTERVAL" &
            local sleep_pid=$!
            wait "$sleep_pid" || true
            if kill -0 "$sleep_pid" 2>/dev/null; then
                kill "$sleep_pid" 2>/dev/null || true
                wait "$sleep_pid" 2>/dev/null || true
            fi
        fi
    done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
