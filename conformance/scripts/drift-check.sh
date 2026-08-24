#!/usr/bin/env bash
# Upstream drift detection: compare the snapshot pinned in PROTOCOL_BASELINE
# against the current upstream state.
#
# Exits non-zero when a normative file changed, so a scheduled CI job can open an
# issue. The client implementation must not silently drift with upstream: changes
# have to be seen and assessed by a human.
#
# Usage: scripts/drift-check.sh [/path/to/Nowhere-clone]
#        NOWHERE_CLONE=/path/to/Nowhere scripts/drift-check.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Upstream clone location. This suite is published, so it must not assume any
# particular layout outside its own repository: set NOWHERE_CLONE to a checkout
# of NodePassProject/Nowhere at the pinned baseline. The default suits a
# side-by-side checkout next to the client repository.
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
CLONE="${1:-$NOWHERE_CLONE}"
BASELINE="$ROOT/PROTOCOL_BASELINE"

fail() { echo "ERROR: $*" >&2; exit 2; }
[ -f "$BASELINE" ] || fail "missing $BASELINE"
[ -d "$CLONE/.git" ] || fail "$CLONE is not a git repository (clone NodePassProject/Nowhere first)"

value() { grep -E "^$1=" "$BASELINE" | head -1 | cut -d= -f2-; }
BRANCH="$(value UPSTREAM_BRANCH)"
PINNED="$(value UPSTREAM_COMMIT)"
SPEC_FILES="$(value SPEC_FILES)"
TAG="$(value UPSTREAM_TAG)"

echo "Baseline: ${TAG:-$BRANCH} @ ${PINNED:0:8}"

# The tracked branch may be deleted (e.g. a feature branch merged and cleaned up).
# That is not a fetch failure - it is itself the signal that upstream changed
# structurally, and it has to be reported clearly.
if ! git -C "$CLONE" fetch --quiet --depth 100 --tags origin "$BRANCH" 2>/dev/null; then
    echo "NOTE: origin/$BRANCH no longer exists - most likely merged or renamed."
    echo "      Confirm where upstream landed (check tags and main), then update PROTOCOL_BASELINE."
    git -C "$CLONE" ls-remote --heads --tags origin 2>/dev/null | tail -12 || true
    exit 1
fi
HEAD_SHA="$(git -C "$CLONE" rev-parse FETCH_HEAD)"

# The baseline may record a short sha; compare by prefix.
if [ "${HEAD_SHA:0:${#PINNED}}" = "$PINNED" ]; then
    echo "PASS: upstream has not moved"
    exit 0
fi

echo "Upstream advanced to ${HEAD_SHA:0:8}"
echo

# Only normative changes are worth interrupting development for; implementation
# changes do not affect client conformance.
DRIFTED=0
for spec in $SPEC_FILES; do
    if ! git -C "$CLONE" diff --quiet "$PINNED" "$HEAD_SHA" -- "$spec" 2>/dev/null; then
        LINES="$(git -C "$CLONE" diff --numstat "$PINNED" "$HEAD_SHA" -- "$spec" | awk '{print "+"$1" -"$2}')"
        echo "  CHANGED  $spec ($LINES)"
        DRIFTED=1
    fi
done

if [ "$DRIFTED" -eq 0 ]; then
    echo "PASS: no normative file changed - only implementation or surrounding docs moved."
    echo "      No action needed - the baseline stays pinned. See the tracking"
    echo "      policy in PROTOCOL_BASELINE before advancing it."
    exit 0
fi

echo
echo "Normative files changed. A human must assess before PROTOCOL_BASELINE is updated."
echo "Inspect the diff:"
echo "  git -C $CLONE diff $PINNED $HEAD_SHA -- docs/protocol.md"
echo
echo "After assessing:"
echo "  1. re-run scripts/verify-vectors.py to check the fixed vectors still hold;"
echo "  2. re-run scripts/smoke-local.sh to check end-to-end still works;"
echo "  3. update UPSTREAM_COMMIT and the notes in PROTOCOL_BASELINE."
exit 1
