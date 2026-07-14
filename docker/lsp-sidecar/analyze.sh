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

[[ "$WORKER_COUNT" =~ ^[1-9][0-9]*$ ]] || WORKER_COUNT=2
[[ "$PROJECT_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || PROJECT_TIMEOUT=300

enabled() {
    case "${1,,}" in
        1|true|yes|on) return 0 ;;
        *)             return 1 ;;
    esac
}

analyze_project() {
    local project_root=$1
    local project_id=$2
    local cache_path="$CACHE_DIR/$project_id"
    mkdir -p "$cache_path"
    local start_time
    start_time=$(date +%s)

    echo "Analyzing project $project_id at $project_root"

    # clojure-lsp dumps "Read-only file system" warnings to stdout, polluting
    # the EDN output. Pipe through grep -v to strip them. Also use a writable
    # kondo cache dir to prevent sync_cache errors on read-only mounts.
    local kondo_cache="$CACHE_DIR/kondo-cache/$project_id"
    mkdir -p "$kondo_cache"

    # Force plain classpath (no aliases) via --settings :project-specs.
    # clojure-lsp deep-merges --settings OVER the project's .lsp/config.edn,
    # replacing only :project-specs and keeping the rest (:source-paths, etc).
    # Covers both build tools: deps.edn -> `clojure -Spath`, project.clj ->
    # `lein classpath`. clojure-lsp runs every spec whose :project-path exists
    # and unions the results, so a lein-only, a deps-only, or a mixed project
    # all resolve. Skip the override only when the project already declares its
    # own :project-specs (EDN reader, not regex, to avoid false positives).
    local settings='{:project-specs [{:project-path "deps.edn" :classpath-cmd ["clojure" "-Spath"]} {:project-path "project.clj" :classpath-cmd ["lein" "classpath"]}]}'
    local cfg="$project_root/.lsp/config.edn"
    if [ -f "$cfg" ] && bb -e '(->> *command-line-args* first slurp clojure.edn/read-string :project-specs some? println)' "$cfg" 2>/dev/null | grep -qx true; then
        settings='{}'
    fi

    if timeout --signal=TERM --kill-after=15s "${PROJECT_TIMEOUT}s" \
        java $JAVA_OPTS -jar "$LSP_JAR" dump --project-root "$project_root" \
        --output '{:format :edn :filter-keys [:analysis :dep-graph]}' \
        --analysis '{:type :project-only}' \
        --settings "$settings" \
        2>"$cache_path/dump.log" \
        | grep -v 'Read-only file system' \
        > "$cache_path/dump.edn.tmp"; then
        local end_time
        end_time=$(date +%s)
        local duration_ms=$(( (end_time - start_time) * 1000 ))
        mv "$cache_path/dump.edn.tmp" "$cache_path/dump.edn"

        # Post-process: extract focused data files from monolithic dump.
        # This avoids 40+ min EDN parse on the host JVM side.
        echo "Extracting focused data files for $project_id..."
        if bb /usr/local/bin/extract.bb "$cache_path"; then
            echo "Extraction completed for $project_id"
        else
            echo "Extraction failed for $project_id (non-fatal, dump.edn still available)"
        fi

        local final_end
        final_end=$(date +%s)
        local completed_at_ms
        completed_at_ms=$(date +%s%3N)
        local total_ms=$(( (final_end - start_time) * 1000 ))
        echo "{:timestamp $start_time :completed-at-ms $completed_at_ms :duration-ms $total_ms :project-root \"$project_root\" :project-id \"$project_id\" :status :ok :extracted true}" > "$cache_path/meta.edn"
        echo "Analysis + extraction successful for $project_id (${total_ms}ms)"
    else
        local exit_code=$?
        local completed_at_ms
        completed_at_ms=$(date +%s%3N)
        rm -f "$cache_path/dump.edn.tmp" \
              "$cache_path/dump.edn" \
              "$cache_path/var-defs.edn" \
              "$cache_path/call-graph.edn" \
              "$cache_path/ns-graph.edn" \
              "$cache_path/ns-defs.edn" \
              "$cache_path/summary.edn"
        echo "{:timestamp $start_time :completed-at-ms $completed_at_ms :project-root \"$project_root\" :project-id \"$project_id\" :status :error :exit-code $exit_code}" > "$cache_path/meta.edn"
        echo "Analysis failed for $project_id with exit code $exit_code"
    fi
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
            local rel="${dir#$WORKSPACE/}"
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

echo "Starting clojure-lsp sidecar analysis with interval: ${INTERVAL}s"
echo "Workspace: $WORKSPACE"
echo "Cache directory: $CACHE_DIR"
echo "Request file: $REQUEST_FILE"
echo "LSP JAR: $LSP_JAR"
echo "Auto-discovery: $AUTO_DISCOVER"
echo "On-demand workers: $WORKER_COUNT"
echo "Per-project timeout: ${PROJECT_TIMEOUT}s"

# Flag for immediate re-run on SIGHUP
rerun_immediately=0

main() {
    trap 'rerun_immediately=1' SIGHUP

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
