#!/usr/bin/env bash
# NW-Q-04: the Kotlin implementation and the Rust one, same Portal, same cases,
# outcomes compared.
#
# The conformance vectors prove this client agrees with a reading of the
# specification. They cannot prove it agrees with the implementation everyone
# else is talking to, because the vectors and the client share an author and a
# misreading would be transcribed into both. The oracle has no such ancestry:
# it is the reference implementation, and where the two differ, one of them is
# wrong in a way no amount of re-reading the spec would have surfaced.
#
# Both sides report in one alphabet — a SOCKS5 reply code, plus the SHA-256 of
# anything that came back. The code is the oracle's only observable view of a
# rejection (its SOCKS front end maps the Portal's SetupResult onto it), so this
# side is put through the same mapping rather than the oracle being asked for
# something it cannot say. See OracleDifferentialTest for that table and why it
# lives in the harness rather than in the client.
#
# Usage: conformance/scripts/oracle-diff.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"

KEY="${ORACLE_KEY:-oracle-diff-key}"
PORTAL_PORT="${ORACLE_PORTAL_PORT:-22079}"
SOCKS_PORT="${ORACLE_SOCKS_PORT:-21090}"
SOCKS_WRONG_PORT="${ORACLE_SOCKS_WRONG_PORT:-21091}"
HTTP_PORT="${ORACLE_HTTP_PORT:-28010}"
UDP_PORT="${ORACLE_UDP_PORT:-28011}"
# A port with nothing behind it, so the Portal answers DIAL_FAILED. Port 1 is
# reserved and never bound by anything on an ordinary machine.
CLOSED_PORT="${ORACLE_CLOSED_PORT:-1}"

RUNDIR="$(mktemp -d)"
PIDS=()

cleanup() {
    for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
    rm -rf "$RUNDIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

wait_for_port() {
    local port=$1 name=$2 tries=0
    until nc -z 127.0.0.1 "$port" 2>/dev/null; do
        tries=$((tries + 1))
        [ "$tries" -gt 150 ] && fail "$name did not listen on $port within 15s"
        sleep 0.1
    done
}

[ -x "$BIN" ] || fail "no nowhere binary at $BIN — run cargo build --release in the Nowhere clone"
echo "Oracle: $("$BIN" --version 2>&1 | head -1)"

# --- Targets both implementations will be pointed at ------------------------
# One blob, served over TCP, and one UDP echo. Both are trivial and both are
# here rather than in a container: this compares two clients, and a name
# resolved in somebody else's namespace would add a variable without adding a
# case.
cat > "$RUNDIR/serve.py" <<'PY'
import hashlib, os, socket, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = int(os.environ.get("BLOB_BYTES", "1048576"))
BLOB = bytes((index * 37 + 11) & 0xFF for index in range(SIZE))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(BLOB)))
        self.send_header("X-Content-Sha256", hashlib.sha256(BLOB).hexdigest())
        self.end_headers()
        self.wfile.write(BLOB)

    def log_message(self, *args):
        pass


def echo(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", port))
    while True:
        data, peer = sock.recvfrom(65535)
        sock.sendto(data, peer)


http_port, udp_port = int(sys.argv[1]), int(sys.argv[2])
threading.Thread(target=echo, args=(udp_port,), daemon=True).start()
print("ready sha256=%s" % hashlib.sha256(BLOB).hexdigest(), flush=True)
ThreadingHTTPServer(("127.0.0.1", http_port), Handler).serve_forever()
PY
python3 "$RUNDIR/serve.py" "$HTTP_PORT" "$UDP_PORT" > "$RUNDIR/serve.log" 2>&1 &
PIDS+=($!)
wait_for_port "$HTTP_PORT" "target service"

# --- One Portal, serving both implementations -------------------------------
"$BIN" "portal://${KEY}@127.0.0.1:${PORTAL_PORT}?log=info" > "$RUNDIR/portal.log" 2>&1 &
PIDS+=($!)
wait_for_port "$PORTAL_PORT" "Portal"

# --- The oracle: two clients, one with the right key and one without --------
"$BIN" "vector://${KEY}@127.0.0.1:${PORTAL_PORT}?up=tcp&down=tcp&socks=127.0.0.1:${SOCKS_PORT}&log=info" \
    > "$RUNDIR/vector.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_PORT" "oracle SOCKS listener"

"$BIN" "vector://not-the-shared-key@127.0.0.1:${PORTAL_PORT}?up=tcp&down=tcp&socks=127.0.0.1:${SOCKS_WRONG_PORT}&log=info" \
    > "$RUNDIR/vector-wrong.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_WRONG_PORT" "oracle SOCKS listener (wrong key)"

echo "OK  Portal :$PORTAL_PORT, oracle SOCKS :$SOCKS_PORT and :$SOCKS_WRONG_PORT, targets :$HTTP_PORT tcp / :$UDP_PORT udp"

# --- Run both sides ---------------------------------------------------------
echo
echo "Running the oracle's cases ..."
python3 "$ROOT/scripts/oracle-cases.py" \
    "127.0.0.1:${SOCKS_PORT}" \
    "127.0.0.1:${SOCKS_WRONG_PORT}" \
    "127.0.0.1:${HTTP_PORT}" \
    "localhost:${HTTP_PORT}" \
    "127.0.0.1:${UDP_PORT}" \
    "127.0.0.1:${CLOSED_PORT}" \
    "$RUNDIR/oracle.tsv" || fail "the oracle's half did not complete"

# --rerun, because the harness's real output is a file in a directory that
# changes every run, and Gradle cannot see it. Without this the second
# invocation is up to date, writes nothing, and the comparison reads an empty
# file — which looked exactly like the harness skipping for want of a Portal.
echo "Running this implementation's cases ..."
( cd "$PROJECT" && \
  NOWHERE_E2E_PORTAL="127.0.0.1:${PORTAL_PORT}" \
  NOWHERE_E2E_KEY="$KEY" \
  ORACLE_TARGET="127.0.0.1:${HTTP_PORT}" \
  ORACLE_TARGET_NAME="localhost:${HTTP_PORT}" \
  ORACLE_UDP="127.0.0.1:${UDP_PORT}" \
  ORACLE_CLOSED="127.0.0.1:${CLOSED_PORT}" \
  ORACLE_DIFF_OUT="$RUNDIR/kotlin.tsv" \
  ./gradlew --no-daemon testDebugUnitTest --rerun \
      --tests 'eu.nodepass.somewhere.conformance.OracleDifferentialTest' ) > "$RUNDIR/gradle.log" 2>&1 \
    || { tail -30 "$RUNDIR/gradle.log"; fail "this implementation's half did not complete"; }

[ -s "$RUNDIR/kotlin.tsv" ] || { tail -30 "$RUNDIR/gradle.log"; fail "no verdicts were written — the harness skipped"; }

# --- Compare ----------------------------------------------------------------
echo
printf '%-22s %-28s %-28s %s\n' "CASE" "ORACLE" "THIS CLIENT" "VERDICT"
printf '%-22s %-28s %-28s %s\n' "----" "------" "-----------" "-------"

DIVERGED=0
while IFS=$'\t' read -r case oracle_reply oracle_digest oracle_note; do
    line="$(grep -F "$(printf '%s\t' "$case")" "$RUNDIR/kotlin.tsv" | head -1)"
    if [ -z "$line" ]; then
        printf '%-22s %-28s %-28s %s\n' "$case" "reply=$oracle_reply" "(absent)" "DIVERGED"
        DIVERGED=$((DIVERGED + 1))
        continue
    fi
    ours_reply="$(printf '%s' "$line" | cut -f2)"
    ours_digest="$(printf '%s' "$line" | cut -f3)"
    ours_note="$(printf '%s' "$line" | cut -f4)"

    oracle_shown="reply=$oracle_reply"
    ours_shown="reply=$ours_reply"
    [ "$oracle_digest" != "-" ] && oracle_shown="$oracle_shown ${oracle_digest:0:12}"
    [ "$ours_digest" != "-" ] && ours_shown="$ours_shown ${ours_digest:0:12}"

    if [ "$oracle_reply" = "$ours_reply" ] && [ "$oracle_digest" = "$ours_digest" ]; then
        printf '%-22s %-28s %-28s %s\n' "$case" "$oracle_shown" "$ours_shown" "agree"
        # The reasons are printed even when the outcomes match, because that is
        # where a difference the shared alphabet cannot express shows up: both
        # sides reach reply 1 for a wrong key, one by reading silence and one by
        # timing out, and only these two lines say so.
        [ "$oracle_note" != "-" ] || [ "$ours_note" != "-" ] &&
            printf '%-22s   %s | %s\n' "" "oracle: $oracle_note" "ours: $ours_note"
    else
        printf '%-22s %-28s %-28s %s\n' "$case" "$oracle_shown" "$ours_shown" "DIVERGED"
        printf '%-22s   oracle: %s\n' "" "$oracle_note"
        printf '%-22s   ours:   %s\n' "" "$ours_note"
        DIVERGED=$((DIVERGED + 1))
    fi
done < "$RUNDIR/oracle.tsv"

echo
# Said rather than left to be inferred from the case list. `up` and `down` each
# take tcp or udp, and udp means QUIC, which is L3 — so at L1 there is exactly
# one carrier pair and the other three are not "untested", they are unreachable.
# A run that quietly covered one of four would read as a run that covered them
# all.
echo "Carrier pairs: tcp/tcp only. up=udp and down=udp select QUIC, which L1 does not implement."
echo
if [ "$DIVERGED" -eq 0 ]; then
    echo "PASS: both implementations behaved identically on every case"
    exit 0
fi
fail "$DIVERGED case(s) diverged from the oracle"
