#!/usr/bin/env bash
set -euo pipefail

# Default values
WORKSPACE=${WORKSPACE:-/workspace}
CACHE_DIR=${CACHE_DIR:-/cache}
INTERVAL=${ANALYSIS_INTERVAL_SECONDS:-300}
LSP_JAR=/opt/clojure-lsp.jar
REQUEST_FILE="$CACHE_DIR/_request.edn"

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

    if java $JAVA_OPTS -jar "$LSP_JAR" dump --project-root "$project_root" \
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
        local total_ms=$(( (final_end - start_time) * 1000 ))
        echo "{:timestamp $start_time :duration-ms $total_ms :project-root \"$project_root\" :project-id \"$project_id\" :status :ok :extracted true}" > "$cache_path/meta.edn"
        echo "Analysis + extraction successful for $project_id (${total_ms}ms)"
    else
        local exit_code=$?
        rm -f "$cache_path/dump.edn.tmp"
        echo "{:timestamp $start_time :project-root \"$project_root\" :project-id \"$project_id\" :status :error :exit-code $exit_code}" > "$cache_path/meta.edn"
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

# Process dynamic request file (written by MCP tool, consumed here)
# Format: one project-id per line
process_requests() {
    if [ -f "$REQUEST_FILE" ]; then
        echo "Processing dynamic request: $REQUEST_FILE"
        while IFS= read -r project_id; do
            # Strip whitespace and skip empty/comment lines
            project_id=$(echo "$project_id" | tr -d '[:space:]')
            [[ -z "$project_id" || "$project_id" == \#* ]] && continue
            local project_root="$WORKSPACE/$project_id"
            if [ -d "$project_root" ]; then
                analyze_project "$project_root" "$project_id"
            else
                echo "Requested project not found: $project_root"
            fi
        done < "$REQUEST_FILE"
        rm -f "$REQUEST_FILE"
        return 0
    fi
    return 1
}

echo "Starting clojure-lsp sidecar analysis with interval: ${INTERVAL}s"
echo "Workspace: $WORKSPACE"
echo "Cache directory: $CACHE_DIR"
echo "Request file: $REQUEST_FILE"
echo "LSP JAR: $LSP_JAR"

# Flag for immediate re-run on SIGHUP
rerun_immediately=0

trap 'rerun_immediately=1' SIGHUP

while true; do
    # Check for dynamic requests first (on-demand indexing)
    if ! process_requests; then
        # No requests — run scheduled discovery
        discover_projects | while IFS=: read -r project_dir project_id; do
            analyze_project "$project_dir" "$project_id"
        done
    fi

    if [ $rerun_immediately -eq 1 ]; then
        rerun_immediately=0
        echo "Re-running immediately due to SIGHUP"
    else
        echo "Sleeping for ${INTERVAL}s"
        sleep "$INTERVAL" &
        wait $! || true
    fi
done
