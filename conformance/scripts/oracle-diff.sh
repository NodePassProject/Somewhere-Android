#!/usr/bin/env bash
# The Kotlin implementation and the Rust one, same Portals, same cases,
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
# Every case runs over TLS and QUIC, each against a Portal with Morph on and
# one with Morph off. Two Portals rather than one because Morph is not
# negotiated: "peers must agree", and a Portal is one or the other.
#
# The `burst` cases open sixteen flows at once and hold them open together. At
# the instant the origin releases them it counts the TCP connections to the
# Portal: sixteen over TLS — "TLS uses one connection per Flow" — and none over
# QUIC, where every flow is a stream on one UDP association. Counted by the
# harness, because that is the number a broken client would get wrong in its
# own favour.
#
# Usage: conformance/scripts/oracle-diff.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"

# v3 keys are 32 lowercase hex characters; the wrong one is too, so that what
# fails is the key and not its spelling.
KEY="${ORACLE_KEY:-00112233445566778899aabbccddeeff}"
WRONG_KEY="ffffffffffffffffffffffffffffffff"
PORTAL_PORT="${ORACLE_PORTAL_PORT:-22079}"
NOMORPH_PORTAL_PORT="${ORACLE_NOMORPH_PORTAL_PORT:-22080}"
SOCKS_BASE="${ORACLE_SOCKS_BASE:-21090}"
HTTP_PORT="${ORACLE_HTTP_PORT:-28010}"
UDP_PORT="${ORACLE_UDP_PORT:-28011}"
# One burst origin per (client, carrier). The port is the label.
HOLD_ORACLE_TLS="${ORACLE_HOLD_A:-28012}"
HOLD_ORACLE_QUIC="${ORACLE_HOLD_B:-28013}"
HOLD_OURS_TLS="${ORACLE_HOLD_C:-28014}"
HOLD_OURS_QUIC="${ORACLE_HOLD_D:-28015}"
BURST_WIDTH="${ORACLE_BURST_WIDTH:-16}"
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
command -v ss >/dev/null || fail "ss is needed to count connections at the burst"
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

# Small on purpose: the burst cases measure how many connections carried
# sixteen flows, and a megabyte apiece would only make that slow.
HOLD = bytes((index * 17 + 3) & 0xFF for index in range(64))
WIDTH = int(os.environ.get("HOLD_WIDTH", "16"))


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


def count_portal_connections(port, label):
    """How many TCP connections to the Portal exist right now.

    Called once, by the barrier, at the instant every flow of a burst is
    provably open — which is the only instant the number means anything.
    Counted from the client end (destination port = the Portal's), so the
    Portal's own listening socket is not one of them.
    """
    import subprocess
    out = subprocess.run(
        ["ss", "-Htn", "state", "established", "( dport = :%d )" % port],
        capture_output=True, text=True,
    ).stdout
    with open(os.path.join(RUNDIR, "connections-%s" % label), "w") as handle:
        handle.write("%d\n" % len([line for line in out.splitlines() if line.strip()]))


def hold_handler(barrier):
    """An origin that answers nobody until WIDTH requests are waiting.

    This is what makes a connection count mean something. Without it the
    sixteen flows are only *probably* concurrent, and a client that opened
    them one after another would still look multiplexed on a fast machine.
    """

    class Held(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            try:
                barrier.wait()
            except threading.BrokenBarrierError:
                # Fewer than WIDTH flows arrived. Answered rather than left
                # hanging, and answered with a failure rather than with the
                # payload, so the case reports the shortfall instead of the
                # run stalling until somebody kills it.
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(HOLD)))
            self.end_headers()
            self.wfile.write(HOLD)

        def log_message(self, *args):
            pass

    return Held


def echo(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", port))
    while True:
        data, peer = sock.recvfrom(65535)
        sock.sendto(data, peer)


http_port, udp_port = int(sys.argv[1]), int(sys.argv[2])
threading.Thread(target=echo, args=(udp_port,), daemon=True).start()

# One barrier per holding origin: each is used by exactly one client in one
# carrier, so a phase that falls short breaks its own barrier and nobody
# else's. The timeout is long enough for a Portal to dial sixteen targets and
# short enough that a stuck run ends by itself.
RUNDIR = os.environ["RUNDIR"]
PORTAL_PORT = int(os.environ["PORTAL_PORT"])
for text in sys.argv[3:]:
    label, _, port_text = text.partition("=")
    action = (lambda label=label: count_portal_connections(PORTAL_PORT, label))
    server = ThreadingHTTPServer(
        ("127.0.0.1", int(port_text)), hold_handler(threading.Barrier(WIDTH, action=action, timeout=60))
    )
    threading.Thread(target=server.serve_forever, daemon=True).start()

print("ready sha256=%s" % hashlib.sha256(BLOB).hexdigest(), flush=True)
ThreadingHTTPServer(("127.0.0.1", http_port), Handler).serve_forever()
PY
RUNDIR="$RUNDIR" PORTAL_PORT="$PORTAL_PORT" HOLD_WIDTH="$BURST_WIDTH" \
    python3 "$RUNDIR/serve.py" "$HTTP_PORT" "$UDP_PORT" \
    "oracle-tls=$HOLD_ORACLE_TLS" "oracle-quic=$HOLD_ORACLE_QUIC" \
    "ours-tls=$HOLD_OURS_TLS" "ours-quic=$HOLD_OURS_QUIC" \
    > "$RUNDIR/serve.log" 2>&1 &
PIDS+=($!)
wait_for_port "$HTTP_PORT" "target service"
for port in "$HOLD_ORACLE_TLS" "$HOLD_ORACLE_QUIC" "$HOLD_OURS_TLS" "$HOLD_OURS_QUIC"; do
    wait_for_port "$port" "burst origin"
done

# --- Two Portals, serving both implementations ------------------------------
# HOST:PORT is TLS and QUIC on the same port. Each logs the fingerprint of the
# certificate it generated, and that fingerprint is the pin both clients use:
# a value neither of them produced.
# Not called through $(...): a Portal started in a subshell records its PID in
# the subshell's PIDS, is never cleaned up, and keeps the port. The next run's
# Portal then fails to bind — after logging a fingerprint of its own, which the
# harness reads as the pin for a Portal that is not the one answering.
nc -z 127.0.0.1 "$PORTAL_PORT" 2>/dev/null && fail "something already listens on $PORTAL_PORT"
nc -z 127.0.0.1 "$NOMORPH_PORTAL_PORT" 2>/dev/null && fail "something already listens on $NOMORPH_PORTAL_PORT"
start_portal() {
    local port=$1 morph=$2 label=$3
    "$BIN" "portal://${KEY}@127.0.0.1:${port}?morph=${morph}" > "$RUNDIR/portal-${label}.log" 2>&1 &
    PIDS+=($!)
    wait_for_port "$port" "Portal ($label)"
}
fingerprint_of() {
    sed -n 's/.*tls certificate fingerprint: \([0-9a-f]\{64\}\).*/\1/p' "$RUNDIR/portal-$1.log" | head -1
}
start_portal "$PORTAL_PORT" 1 morph
start_portal "$NOMORPH_PORTAL_PORT" 0 nomorph
PIN="$(fingerprint_of morph)"
NOMORPH_PIN="$(fingerprint_of nomorph)"
[ -n "$PIN" ] && [ -n "$NOMORPH_PIN" ] || fail "a Portal did not log its certificate fingerprint"

# --- The oracle: one vector per (carrier, Morph, key) -----------------------
NEXT_SOCKS="$SOCKS_BASE"
ORACLE_ARGS=()
start_vector() {
    local flag=$1 key=$2 endpoint=$3 query=$4 label=$5
    local port=$NEXT_SOCKS
    NEXT_SOCKS=$((NEXT_SOCKS + 1))
    "$BIN" "vector://${key}@${endpoint}?in=127.0.0.1:${port}&${query}" > "$RUNDIR/vector-${label}.log" 2>&1 &
    PIDS+=($!)
    wait_for_port "$port" "oracle SOCKS listener ($label)"
    ORACLE_ARGS+=("--socks-${flag}" "127.0.0.1:${port}")
}
start_pair() {
    local prefix=$1 endpoint=$2 query=$3 label=$4
    start_vector "${prefix}right" "$KEY" "$endpoint" "$query" "$label"
    start_vector "${prefix}wrong" "$WRONG_KEY" "$endpoint" "$query" "$label-wrong"
}
start_pair "" "127.0.0.1:${PORTAL_PORT}" "pin=${PIN}" tls
start_pair "nomorph-" "127.0.0.1:${NOMORPH_PORTAL_PORT}" "pin=${NOMORPH_PIN}&morph=0" nomorph-tls

# QUIC only if a host bridge exists on this side. Half a comparison is worse
# than none: it reports a divergence that is only an absence.
QUIC=0
if HOST_QUIC="$("$ROOT/scripts/build-host-quic.sh" 2>/dev/null | sed -n 's/^LIBRARY=//p')" && [ -n "$HOST_QUIC" ]; then
    export SOMEWHERE_QUIC_LIBRARY="$HOST_QUIC"
    start_pair "quic-" "127.0.0.1/udp:${PORTAL_PORT}" "pin=${PIN}" quic
    start_pair "nomorph-quic-" "127.0.0.1/udp:${NOMORPH_PORTAL_PORT}" "pin=${NOMORPH_PIN}&morph=0" nomorph-quic
    QUIC=1
    echo "OK  QUIC bridge for this host, and the QUIC carriers"
else
    echo "--  no host QUIC bridge; the QUIC carriers are not compared"
fi

echo "OK  Portals :$PORTAL_PORT (morph=1) and :$NOMORPH_PORTAL_PORT (morph=0), targets :$HTTP_PORT tcp / :$UDP_PORT udp"

# --- Run both sides ---------------------------------------------------------
echo
echo "Running the oracle's cases ..."
python3 "$ROOT/scripts/oracle-cases.py" \
    "${ORACLE_ARGS[@]}" \
    --target "127.0.0.1:${HTTP_PORT}" \
    --target-name "localhost:${HTTP_PORT}" \
    --udp "127.0.0.1:${UDP_PORT}" \
    --closed "127.0.0.1:${CLOSED_PORT}" \
    --hold-tls "127.0.0.1:${HOLD_ORACLE_TLS}" \
    --hold-quic "127.0.0.1:${HOLD_ORACLE_QUIC}" \
    --width "$BURST_WIDTH" \
    --out "$RUNDIR/oracle.tsv" || fail "the oracle's half did not complete"

# --rerun, because the harness's real output is a file in a directory that
# changes every run, and Gradle cannot see it. Without this the second
# invocation is up to date, writes nothing, and the comparison reads an empty
# file — which looked exactly like the harness skipping for want of a Portal.
#
# One Portal certificate per Portal, and this side is handed both pins.
echo "Running this implementation's cases ..."
( cd "$PROJECT" && \
  NOWHERE_E2E_PORTAL="127.0.0.1:${PORTAL_PORT}" \
  ORACLE_PORTAL_NOMORPH="127.0.0.1:${NOMORPH_PORTAL_PORT}" \
  NOWHERE_E2E_KEY="$KEY" \
  NOWHERE_E2E_PIN="$PIN" \
  ORACLE_NOMORPH_PIN="$NOMORPH_PIN" \
  ORACLE_TARGET="127.0.0.1:${HTTP_PORT}" \
  ORACLE_TARGET_NAME="localhost:${HTTP_PORT}" \
  ORACLE_UDP="127.0.0.1:${UDP_PORT}" \
  ORACLE_CLOSED="127.0.0.1:${CLOSED_PORT}" \
  ORACLE_HOLD_TLS="127.0.0.1:${HOLD_OURS_TLS}" \
  ORACLE_HOLD_QUIC="127.0.0.1:${HOLD_OURS_QUIC}" \
  ORACLE_DIFF_OUT="$RUNDIR/kotlin.tsv" \
  ./gradlew --no-daemon testDebugUnitTest --rerun \
      --tests 'eu.nodepass.somewhere.conformance.OracleDifferentialTest' ) > "$RUNDIR/gradle.log" 2>&1 \
    || { tail -30 "$RUNDIR/gradle.log"; fail "this implementation's half did not complete"; }

[ -s "$RUNDIR/kotlin.tsv" ] || { tail -30 "$RUNDIR/gradle.log"; fail "no verdicts were written — the harness skipped"; }

# --- What the burst origins counted -----------------------------------------
# Appended to both halves so the count is compared by the same loop as
# everything else: a connection count is an outcome, and there is no reason for
# it to have a second comparison of its own.
count() { cat "$RUNDIR/connections-$1" 2>/dev/null | tr -d ' \n' || true; }
note_for() { echo "$BURST_WIDTH flows over $1 TCP connection(s) to the Portal"; }
append_counts() {
    local who=$1 out=$2
    local tls; tls="$(count "$who-tls")"
    printf '%s\t%s\t-\t%s\n' "tls_connections" "${tls:-?}" "$(note_for "${tls:-?}")" >> "$out"
    if [ "$QUIC" -eq 1 ]; then
        local quic; quic="$(count "$who-quic")"
        printf '%s\t%s\t-\t%s\n' "quic_connections" "${quic:-?}" "$(note_for "${quic:-?}")" >> "$out"
    fi
}
append_counts oracle "$RUNDIR/oracle.tsv"
append_counts ours "$RUNDIR/kotlin.tsv"

# --- Compare ----------------------------------------------------------------
echo
printf '%-26s %-28s %-28s %s\n' "CASE" "ORACLE" "THIS CLIENT" "VERDICT"
printf '%-26s %-28s %-28s %s\n' "----" "------" "-----------" "-------"

DIVERGED=0
while IFS=$'\t' read -r case oracle_reply oracle_digest oracle_note; do
    line="$(grep -F "$(printf '%s\t' "$case")" "$RUNDIR/kotlin.tsv" | head -1)"
    if [ -z "$line" ]; then
        printf '%-26s %-28s %-28s %s\n' "$case" "reply=$oracle_reply" "(absent)" "DIVERGED"
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
        printf '%-26s %-28s %-28s %s\n' "$case" "$oracle_shown" "$ours_shown" "agree"
        # The reasons are printed even when the outcomes match, because that is
        # where a difference the shared alphabet cannot express shows up.
        [ "$oracle_note" != "-" ] || [ "$ours_note" != "-" ] &&
            printf '%-26s   %s | %s\n' "" "oracle: $oracle_note" "ours: $ours_note"
    else
        printf '%-26s %-28s %-28s %s\n' "$case" "$oracle_shown" "$ours_shown" "DIVERGED"
        printf '%-26s   oracle: %s\n' "" "$oracle_note"
        printf '%-26s   ours:   %s\n' "" "$ours_note"
        DIVERGED=$((DIVERGED + 1))
    fi
done < "$RUNDIR/oracle.tsv"

echo
if [ "$QUIC" -eq 1 ]; then
    echo "Carriers: TLS and QUIC, each with Morph on and off."
else
    echo "Carriers: TLS only, with Morph on and off. No host QUIC bridge was built, so QUIC was not compared."
fi

# The two counts agreeing is not enough on its own: a harness that had stopped
# counting would report the same nothing for both clients and the loop above
# would call that agreement. So the numbers are held to what the specification
# says they are.
for who in oracle ours; do
    tls="$(count "$who-tls")"
    [ "$tls" = "$BURST_WIDTH" ] ||
        fail "$who held $BURST_WIDTH TLS flows open over ${tls:-no} TCP connection(s); v3 is one connection per flow"
    if [ "$QUIC" -eq 1 ]; then
        quic="$(count "$who-quic")"
        [ "$quic" = "0" ] ||
            fail "$who held $BURST_WIDTH QUIC flows open with ${quic:-an unknown number of} TCP connection(s) to the Portal; QUIC needs none"
    fi
done
echo "OK  both implementations: $BURST_WIDTH TLS flows on $BURST_WIDTH connections, and QUIC flows on none"

echo
if [ "$DIVERGED" -eq 0 ]; then
    echo "PASS: both implementations behaved identically on every case"
    exit 0
fi
fail "$DIVERGED case(s) diverged from the oracle"
