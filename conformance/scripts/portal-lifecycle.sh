#!/usr/bin/env bash
# The two L1 cases that need the Portal to stop being there.
#
#   NW-P-11  a connection that authenticates and then says nothing is reclaimed
#            after forty seconds
#   —        a new flow succeeds after the Portal has restarted, with nothing
#            asked of the user
#
# Separate from the unit gate because the first one takes forty seconds of
# deliberate silence and the second starts and kills processes. Neither belongs
# in a suite that runs on every commit, and both belong somewhere.
#
# Usage: conformance/scripts/portal-lifecycle.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"

[ -x "$BIN" ] || { echo "FAIL: no nowhere binary at $BIN" >&2; exit 1; }
echo "Portal binary: $("$BIN" --version 2>&1 | head -1)"
echo "These cases start and stop their own Portals; the reclaim case waits out a 40s deadline."
echo

# --rerun for the same reason oracle-diff.sh needs it: the interesting output is
# a process lifecycle, and Gradle has no way to know the world changed.
cd "$PROJECT" && NOWHERE_BIN="$BIN" ./gradlew --no-daemon testDebugUnitTest --rerun \
    --tests 'eu.nodepass.somewhere.conformance.PortalLifecycleTest' -i 2>&1 \
    | grep -E "NW-P-11|PortalLifecycleTest|FAILED|BUILD|tests completed"
exit "${PIPESTATUS[0]}"
