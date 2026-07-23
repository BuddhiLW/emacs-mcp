#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

export WORKSPACE="$tmp/workspace"
export CACHE_DIR="$tmp/cache"
export LSP_WORKER_COUNT=2
mkdir -p "$WORKSPACE/a" "$WORKSPACE/b" "$CACHE_DIR"

# Sourcing exposes the queue functions without starting the infinite loop.
# shellcheck source=docker/lsp-sidecar/analyze.sh
source "$here/analyze.sh"

write_health ok
test "$(read_job_field "$HEALTH_FILE" status)" = ':ok'
heartbeat_at=$(read_job_field "$HEALTH_FILE" heartbeat-at-ms)
[[ "$heartbeat_at" =~ ^[0-9]+$ ]]

claim_test="$REQUEST_FILE"
printf 'a\n' > "$claim_test"
claimed=$(claim_request_file)
printf 'b\n' > "$claim_test"
test -f "$claimed"
test "$(sed -n '1p' "$claimed")" = "a"
test "$(sed -n '1p' "$claim_test")" = "b"
rm -f "$claimed" "$claim_test"

mkdir -p "$WORKSPACE/local/app/.lsp" "$WORKSPACE/local/lib/src" "$tmp/outside"
printf '%s\n' \
    '{:aliases {:local-src {:override-deps {demo/lib {:local/root "../lib"}} :extra-paths ["../lib/src"]}}}' \
    > "$WORKSPACE/local/app/deps.edn"
printf '%s\n' \
    '{:project-specs [{:project-path "deps.edn" :classpath-cmd ["clojure" "-Spath"]}]}' \
    > "$WORKSPACE/local/app/.lsp/config.edn"
mounted_local_src "$WORKSPACE/local/app"
local_settings=$(settings_for_project "$WORKSPACE/local/app")
[[ "$local_settings" == *'"-A:local-src"'* ]]
printf '%s\n' \
    '{:project-specs [{:project-path "deps.edn" :classpath-cmd ["clojure" "-A:local-src" "-Spath"]}]}' \
    > "$WORKSPACE/local/app/.lsp/config.edn"
local_settings=$(settings_for_project "$WORKSPACE/local/app")
[[ "$local_settings" == *'"-A:local-src"'* ]]
printf '%s\n' \
    '{:project-specs [{:project-path "deps.edn" :classpath-cmd ["clojure" "-Spath" "-M:local-src"]}]}' \
    > "$WORKSPACE/local/app/.lsp/config.edn"
local_settings=$(settings_for_project "$WORKSPACE/local/app")
[[ "$local_settings" == *'"-A:local-src"'* ]]
printf '%s\n' \
    '{:project-specs [{:project-path "deps.edn" :classpath-cmd ["clojure" "-Sdeps" "{:deps {demo/lib {:local/root \"../lib\"}}}" "-Spath"]}]}' \
    > "$WORKSPACE/local/app/.lsp/config.edn"
local_settings=$(settings_for_project "$WORKSPACE/local/app")
[[ "$local_settings" == *'"-A:local-src"'* ]]

mkdir -p "$WORKSPACE/local/bad"
printf '%s\n' \
    "{:aliases {:local-src {:override-deps {demo/outside {:local/root \"$tmp/outside\"}}}}}" \
    > "$WORKSPACE/local/bad/deps.edn"
if mounted_local_src "$WORKSPACE/local/bad"; then
    printf 'unmounted :local-src target was accepted\n' >&2
    exit 1
fi
bad_settings=$(settings_for_project "$WORKSPACE/local/bad")
[[ "$bad_settings" == *'["clojure" "-Spath"]'* ]]
[[ "$bad_settings" != *'"-A:local-src"'* ]]

mkdir -p "$WORKSPACE/local/custom/.lsp"
printf '%s\n' '{:aliases {:local-src {}}}' > "$WORKSPACE/local/custom/deps.edn"
printf '%s\n' \
    '{:project-specs [{:project-path "deps.edn" :classpath-cmd ["bb" "classpath"]}]}' \
    > "$WORKSPACE/local/custom/.lsp/config.edn"
test "$(settings_for_project "$WORKSPACE/local/custom")" = '{}'

mkdir -p "$WORKSPACE/cancel" "$tmp/fake-bin"
printf '%s\n' '#!/usr/bin/env bash' \
    'trap '\''exit 143'\'' TERM' \
    'sleep 10' \
    'printf '\''{:analysis {} :dep-graph {}}\n'\''' \
    > "$tmp/fake-bin/java"
chmod +x "$tmp/fake-bin/java"
PATH="$tmp/fake-bin:$PATH"
export PATH

analyze_project "$WORKSPACE/cancel" cancel &
cancel_worker=$!
cancel_job="$CACHE_DIR/cancel/job.edn"
for _ in $(seq 1 100); do
    cancel_job_id=$(read_job_field "$cancel_job" job-id 2>/dev/null || true)
    cancel_process_pid=$(read_job_field "$cancel_job" process-pid 2>/dev/null || true)
    [ -n "$cancel_job_id" ] && [ -n "$cancel_process_pid" ] && break
    sleep 0.01
done
test -n "${cancel_job_id:-}"
test -n "${cancel_process_pid:-}"
test "$(read_job_field "$cancel_job" status)" = ':running'
write_edn_atomic \
    "$CACHE_DIR/cancel/cancel.edn" \
    "{:job-id \"$cancel_job_id\" :project-id \"cancel\" :requested-at-ms $(date +%s%3N)}"
kill -TERM "$cancel_worker"
wait "$cancel_worker"
test "$(read_job_field "$cancel_job" status)" = ':cancelled'
test "$(read_job_field "$CACHE_DIR/cancel/meta.edn" status)" = ':cancelled'
test ! -e "$CACHE_DIR/cancel/cancel.edn"
cancel_queue_latency=$(read_job_field "$cancel_job" queue-latency-ms)
[[ "$cancel_queue_latency" =~ ^[0-9]+$ ]]
test "$cancel_queue_latency" = \
    "$(read_job_field "$CACHE_DIR/cancel/meta.edn" queue-latency-ms)"

seen="$tmp/seen"
analyze_project() {
    if [ "$2" = "a" ]; then
        sleep 0.1
    fi
    printf '%s\n' "$2" >> "$seen"
}

printf 'b\na\na\n' > "$REQUEST_FILE"
trap 'rerun_immediately=1' HUP
(sleep 0.02; kill -HUP $$) &
signaler=$!
process_requests
wait "$signaler"
test ! -e "$REQUEST_FILE"
test "$(sort "$seen")" = $'a\nb'

printf 'analyze_test.sh: PASS\n'
