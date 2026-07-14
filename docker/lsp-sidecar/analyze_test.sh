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
source "$here/analyze.sh"

claim_test="$REQUEST_FILE"
printf 'a\n' > "$claim_test"
claimed=$(claim_request_file)
printf 'b\n' > "$claim_test"
test -f "$claimed"
test "$(sed -n '1p' "$claimed")" = "a"
test "$(sed -n '1p' "$claim_test")" = "b"
rm -f "$claimed" "$claim_test"

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
