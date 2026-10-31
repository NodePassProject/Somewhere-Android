#!/usr/bin/env bash
# Upstream drift detection: compare the snapshot pinned in PROTOCOL_BASELINE
# against the current upstream state.
#
# Exits non-zero when a normative file changed, so a scheduled CI job can open an
# issue. The client implementation must not silently drift with upstream: changes
# have to be seen and assessed by a human.
#
# Two repositories are watched. Since v3.1.0 the Core specification lives in
# NodePassProject/Nowhere-Protocol (PROTOCOL.md), and Nowhere's own
# docs/protocol.md is a page of links to it: watching only the implementation
# repository would have reported "no change" for every future Core edit. The
# implementation repository still carries the Prelude Extension and the
# configuration, security and interoperability rules a peer must keep.
#
# Usage: scripts/drift-check.sh [/path/to/Nowhere-clone] [/path/to/Nowhere-Protocol-clone]
#        NOWHERE_CLONE=... NOWHERE_SPEC_CLONE=... scripts/drift-check.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Upstream clone locations. This suite is published, so it must not assume any
# particular layout outside its own repository: set NOWHERE_CLONE and
# NOWHERE_SPEC_CLONE to checkouts of the two repositories. The defaults suit
# side-by-side checkouts next to the client repository.
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
NOWHERE_SPEC_CLONE="${NOWHERE_SPEC_CLONE:-$ROOT/../../Nowhere-Protocol}"
CLONE="${1:-$NOWHERE_CLONE}"
SPEC_CLONE="${2:-$NOWHERE_SPEC_CLONE}"
BASELINE="$ROOT/PROTOCOL_BASELINE"

fail() { echo "ERROR: $*" >&2; exit 2; }
[ -f "$BASELINE" ] || fail "missing $BASELINE"
[ -d "$CLONE/.git" ] || fail "$CLONE is not a git repository (clone NodePassProject/Nowhere first)"
[ -d "$SPEC_CLONE/.git" ] || fail "$SPEC_CLONE is not a git repository (clone NodePassProject/Nowhere-Protocol first)"

value() { grep -E "^$1=" "$BASELINE" | head -1 | cut -d= -f2-; }

# check <label> <clone> <branch> <pinned> <files...>
# Prints what moved. Returns 0 when nothing normative did, 1 when something
# did, 3 when the branch is gone.
check() {
    local label="$1" clone="$2" branch="$3" pinned="$4"
    shift 4

    # The tracked branch may be deleted (e.g. a feature branch merged and
    # cleaned up). That is not a fetch failure - it is itself the signal that
    # upstream changed structurally, and it has to be reported clearly.
    if ! git -C "$clone" fetch --quiet --depth 100 --tags origin "$branch" 2>/dev/null; then
        echo "NOTE: $label origin/$branch no longer exists - most likely merged or renamed."
        echo "      Confirm where upstream landed (check tags and main), then update PROTOCOL_BASELINE."
        git -C "$clone" ls-remote --heads --tags origin 2>/dev/null | tail -12 || true
        return 3
    fi

    local head
    head="$(git -C "$clone" rev-parse FETCH_HEAD)"
    if [ "${head:0:${#pinned}}" = "$pinned" ]; then
        echo "  $label: upstream has not moved"
        return 0
    fi

    echo "  $label: upstream advanced to ${head:0:8}"
    local drifted=0 spec lines
    for spec in "$@"; do
        if ! git -C "$clone" diff --quiet "$pinned" "$head" -- "$spec" 2>/dev/null; then
            lines="$(git -C "$clone" diff --numstat "$pinned" "$head" -- "$spec" | awk '{print "+"$1" -"$2}')"
            echo "    CHANGED  $spec ($lines)"
            drifted=1
        fi
    done
    if [ "$drifted" -eq 1 ]; then
        echo "    Inspect: git -C $clone diff $pinned $head -- $*"
        return 1
    fi
    return 0
}

TAG="$(value UPSTREAM_TAG)"
PINNED="$(value UPSTREAM_COMMIT)"
SPEC_PINNED="$(value SPEC_REPO_COMMIT)"
echo "Baseline: ${TAG:-$(value UPSTREAM_BRANCH)} @ ${PINNED:0:8}; specification @ ${SPEC_PINNED:0:8}"

# shellcheck disable=SC2046 # the file lists are space-separated on purpose
check "$(value SPEC_REPO)" "$SPEC_CLONE" "$(value SPEC_REPO_BRANCH)" "$SPEC_PINNED" $(value SPEC_REPO_FILES)
SPEC_CODE=$?
# shellcheck disable=SC2046
check "$(value UPSTREAM_REPO)" "$CLONE" "$(value UPSTREAM_BRANCH)" "$PINNED" $(value SPEC_FILES)
IMPL_CODE=$?

if [ "$SPEC_CODE" -eq 3 ] || [ "$IMPL_CODE" -eq 3 ]; then
    exit 1
fi
if [ "$SPEC_CODE" -eq 0 ] && [ "$IMPL_CODE" -eq 0 ]; then
    echo "PASS: no normative file changed - only implementation or surrounding docs moved, if anything."
    echo "      No action needed - the baseline stays pinned. See the tracking"
    echo "      policy in PROTOCOL_BASELINE before advancing it."
    exit 0
fi

echo
echo "Normative files changed. A human must assess before PROTOCOL_BASELINE is updated."
echo
echo "After assessing:"
echo "  1. re-run scripts/verify-vectors.py to check the fixed vectors still hold;"
echo "  2. re-run scripts/smoke-local.sh and scripts/oracle-diff.sh against the new oracle;"
echo "  3. update the pinned commits and the notes in PROTOCOL_BASELINE."
exit 1
