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
# Every case runs twice, once over dedicated TLS lanes and once over a Mux
# carrier, and the Mux set is prefixed `mux_`. That the two sets agree is the
# claim: Mux moves the same frames over a shared connection, so a case whose
# outcome changes with the carrier has found something.
#
# The `burst` cases are the ones Mux exists for. Sixteen flows are opened at
# once on each side and held open together by an origin that answers nobody
# until all sixteen have arrived — flows that merely happen quickly say nothing
# about how many connections carried them. The connections are then counted
# **at the Portal**, from the source addresses in its own exchange lines, which
# is the one number neither implementation can flatter itself with.
#
# Four burst origins rather than one, because the port is what attributes a
# connection: the Portal logs the address each flow was dialled to, so counting
# per origin port says which client and which carrier without the harness
# having to reason about when each phase ran.
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
SOCKS_MUX_PORT="${ORACLE_SOCKS_MUX_PORT:-21092}"
SOCKS_MUX_WRONG_PORT="${ORACLE_SOCKS_MUX_WRONG_PORT:-21093}"
# The three QUIC combinations, each with a wrong-key twin. Six more listeners
# rather than a switch, for the same reason the Mux pair are separate: a
# vector's carrier is chosen by its URL and one process cannot be two.
SOCKS_QUIC_PORT="${ORACLE_SOCKS_QUIC_PORT:-21094}"
SOCKS_QUIC_WRONG_PORT="${ORACLE_SOCKS_QUIC_WRONG_PORT:-21095}"
SOCKS_SPLIT_UP_PORT="${ORACLE_SOCKS_SPLIT_UP_PORT:-21096}"
SOCKS_SPLIT_UP_WRONG_PORT="${ORACLE_SOCKS_SPLIT_UP_WRONG_PORT:-21097}"
SOCKS_SPLIT_DOWN_PORT="${ORACLE_SOCKS_SPLIT_DOWN_PORT:-21098}"
SOCKS_SPLIT_DOWN_WRONG_PORT="${ORACLE_SOCKS_SPLIT_DOWN_WRONG_PORT:-21099}"
HTTP_PORT="${ORACLE_HTTP_PORT:-28010}"
UDP_PORT="${ORACLE_UDP_PORT:-28011}"
# One burst origin per (client, carrier). See the header: the port is the label.
HOLD_ORACLE_DEDICATED="${ORACLE_HOLD_A:-28012}"
HOLD_ORACLE_MUX="${ORACLE_HOLD_B:-28013}"
HOLD_OURS_DEDICATED="${ORACLE_HOLD_C:-28014}"
HOLD_OURS_MUX="${ORACLE_HOLD_D:-28015}"
# Four shards' worth at upstream's stated density of four active flows each.
BURST_WIDTH="${ORACLE_BURST_WIDTH:-16}"
SHARD_DENSITY=4
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
for text in sys.argv[3:]:
    server = ThreadingHTTPServer(
        ("127.0.0.1", int(text)), hold_handler(threading.Barrier(WIDTH, timeout=60))
    )
    threading.Thread(target=server.serve_forever, daemon=True).start()

print("ready sha256=%s" % hashlib.sha256(BLOB).hexdigest(), flush=True)
ThreadingHTTPServer(("127.0.0.1", http_port), Handler).serve_forever()
PY
HOLD_WIDTH="$BURST_WIDTH" python3 "$RUNDIR/serve.py" "$HTTP_PORT" "$UDP_PORT" \
    "$HOLD_ORACLE_DEDICATED" "$HOLD_ORACLE_MUX" "$HOLD_OURS_DEDICATED" "$HOLD_OURS_MUX" \
    > "$RUNDIR/serve.log" 2>&1 &
PIDS+=($!)
wait_for_port "$HTTP_PORT" "target service"
for port in "$HOLD_ORACLE_DEDICATED" "$HOLD_ORACLE_MUX" "$HOLD_OURS_DEDICATED" "$HOLD_OURS_MUX"; do
    wait_for_port "$port" "burst origin"
done

# --- One Portal, serving both implementations -------------------------------
# `log=debug` rather than info: the exchange lines carry the source address of
# every flow, and those addresses are how the burst cases are counted. At info
# the Portal is silent about them and the count is zero for both sides, which
# would read as agreement.
"$BIN" "portal://${KEY}@127.0.0.1:${PORTAL_PORT}?log=debug" > "$RUNDIR/portal.log" 2>&1 &
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

# The same two again, multiplexed. Separate processes rather than a switch,
# because a vector's carrier is chosen by its URL and one process cannot be
# both — and because the Portal accepts a marked carrier and an unmarked lane
# on the same listener, which is itself worth exercising.
"$BIN" "vector://${KEY}@127.0.0.1:${PORTAL_PORT}?up=tcp&down=tcp&mux=1&socks=127.0.0.1:${SOCKS_MUX_PORT}&log=info" \
    > "$RUNDIR/vector-mux.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_MUX_PORT" "oracle SOCKS listener (mux=1)"

"$BIN" "vector://not-the-shared-key@127.0.0.1:${PORTAL_PORT}?up=tcp&down=tcp&mux=1&socks=127.0.0.1:${SOCKS_MUX_WRONG_PORT}&log=info" \
    > "$RUNDIR/vector-mux-wrong.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_MUX_WRONG_PORT" "oracle SOCKS listener (mux=1, wrong key)"

# --- The QUIC combinations -------------------------------------------------
# Built only if a host bridge exists on this side. Half a comparison is worse
# than none: it reports a divergence that is only an absence.
QUIC_ARGS=()
if HOST_QUIC="$("$ROOT/scripts/build-host-quic.sh" 2>/dev/null | sed -n 's/^LIBRARY=//p')" && [ -n "$HOST_QUIC" ]; then
    export SOMEWHERE_QUIC_LIBRARY="$HOST_QUIC"
    start_oracle_vector() {
        local up=$1 down=$2 port=$3 wrong_port=$4 label=$5
        "$BIN" "vector://${KEY}@127.0.0.1:${PORTAL_PORT}?up=${up}&down=${down}&socks=127.0.0.1:${port}&log=info" \
            > "$RUNDIR/vector-${label}.log" 2>&1 &
        PIDS+=($!)
        wait_for_port "$port" "oracle SOCKS listener ($label)"
        "$BIN" "vector://not-the-shared-key@127.0.0.1:${PORTAL_PORT}?up=${up}&down=${down}&socks=127.0.0.1:${wrong_port}&log=info" \
            > "$RUNDIR/vector-${label}-wrong.log" 2>&1 &
        PIDS+=($!)
        wait_for_port "$wrong_port" "oracle SOCKS listener ($label, wrong key)"
    }
    start_oracle_vector udp udp "$SOCKS_QUIC_PORT" "$SOCKS_QUIC_WRONG_PORT" quic
    start_oracle_vector udp tcp "$SOCKS_SPLIT_UP_PORT" "$SOCKS_SPLIT_UP_WRONG_PORT" split-up
    start_oracle_vector tcp udp "$SOCKS_SPLIT_DOWN_PORT" "$SOCKS_SPLIT_DOWN_WRONG_PORT" split-down
    QUIC_ARGS=(
        --socks-quic "127.0.0.1:${SOCKS_QUIC_PORT}"
        --socks-quic-wrong-key "127.0.0.1:${SOCKS_QUIC_WRONG_PORT}"
        --socks-split-up "127.0.0.1:${SOCKS_SPLIT_UP_PORT}"
        --socks-split-up-wrong-key "127.0.0.1:${SOCKS_SPLIT_UP_WRONG_PORT}"
        --socks-split-down "127.0.0.1:${SOCKS_SPLIT_DOWN_PORT}"
        --socks-split-down-wrong-key "127.0.0.1:${SOCKS_SPLIT_DOWN_WRONG_PORT}"
    )
    echo "OK  QUIC bridge for this host, and three more oracle carriers"
else
    echo "--  no host QUIC bridge; the QUIC combinations are not compared"
fi

echo "OK  Portal :$PORTAL_PORT, oracle SOCKS :$SOCKS_PORT :$SOCKS_WRONG_PORT :$SOCKS_MUX_PORT :$SOCKS_MUX_WRONG_PORT, targets :$HTTP_PORT tcp / :$UDP_PORT udp"

# --- Run both sides ---------------------------------------------------------
echo
echo "Running the oracle's cases ..."
python3 "$ROOT/scripts/oracle-cases.py" \
    --socks "127.0.0.1:${SOCKS_PORT}" \
    --socks-wrong-key "127.0.0.1:${SOCKS_WRONG_PORT}" \
    --socks-mux "127.0.0.1:${SOCKS_MUX_PORT}" \
    --socks-mux-wrong-key "127.0.0.1:${SOCKS_MUX_WRONG_PORT}" \
    ${QUIC_ARGS[@]+"${QUIC_ARGS[@]}"} \
    --target "127.0.0.1:${HTTP_PORT}" \
    --target-name "localhost:${HTTP_PORT}" \
    --udp "127.0.0.1:${UDP_PORT}" \
    --closed "127.0.0.1:${CLOSED_PORT}" \
    --hold-dedicated "127.0.0.1:${HOLD_ORACLE_DEDICATED}" \
    --hold-mux "127.0.0.1:${HOLD_ORACLE_MUX}" \
    --width "$BURST_WIDTH" \
    --out "$RUNDIR/oracle.tsv" || fail "the oracle's half did not complete"

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
  ORACLE_HOLD_DEDICATED="127.0.0.1:${HOLD_OURS_DEDICATED}" \
  ORACLE_HOLD_MUX="127.0.0.1:${HOLD_OURS_MUX}" \
  ORACLE_DIFF_OUT="$RUNDIR/kotlin.tsv" \
  ./gradlew --no-daemon testDebugUnitTest --rerun \
      --tests 'eu.nodepass.somewhere.conformance.OracleDifferentialTest' ) > "$RUNDIR/gradle.log" 2>&1 \
    || { tail -30 "$RUNDIR/gradle.log"; fail "this implementation's half did not complete"; }

[ -s "$RUNDIR/kotlin.tsv" ] || { tail -30 "$RUNDIR/gradle.log"; fail "no verdicts were written — the harness skipped"; }

# --- What the Portal saw ----------------------------------------------------
# Distinct source addresses on the flows dialled to one burst origin. The
# Portal's exchange line names the address its own accept() returned, so this
# counts connections that really existed rather than connections a client
# claims to have opened.
carriers_for() {
    grep "exchange starting" "$RUNDIR/portal.log" 2>/dev/null \
        | grep -oE "UP\[TCP\] [0-9.]+:[0-9]+[^|]*-> 127\.0\.0\.1:$1 " \
        | grep -oE "^UP\[TCP\] [0-9.]+:[0-9]+" \
        | sort -u | wc -l | tr -d ' '
}

ORACLE_DEDICATED_CARRIERS="$(carriers_for "$HOLD_ORACLE_DEDICATED")"
ORACLE_MUX_CARRIERS="$(carriers_for "$HOLD_ORACLE_MUX")"
OURS_DEDICATED_CARRIERS="$(carriers_for "$HOLD_OURS_DEDICATED")"
OURS_MUX_CARRIERS="$(carriers_for "$HOLD_OURS_MUX")"

# Appended to both halves so the count is compared by the same loop as
# everything else: a carrier count is an outcome, and there is no reason for it
# to have a second comparison of its own.
note_for() { echo "$BURST_WIDTH flows over $1 TLS connection(s)"; }
printf '%s\t%s\t-\t%s\n' \
    "dedicated_carriers" "$ORACLE_DEDICATED_CARRIERS" "$(note_for "$ORACLE_DEDICATED_CARRIERS")" \
    "mux_carriers" "$ORACLE_MUX_CARRIERS" "$(note_for "$ORACLE_MUX_CARRIERS")" >> "$RUNDIR/oracle.tsv"
printf '%s\t%s\t-\t%s\n' \
    "dedicated_carriers" "$OURS_DEDICATED_CARRIERS" "$(note_for "$OURS_DEDICATED_CARRIERS")" \
    "mux_carriers" "$OURS_MUX_CARRIERS" "$(note_for "$OURS_MUX_CARRIERS")" >> "$RUNDIR/kotlin.tsv"

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
# take tcp or udp, and udp selects QUIC. Until L3 there was exactly one pair and
# the other three were not "untested" but unreachable, so this line said so
# rather than letting a run that covered one of four read as a run that covered
# them all.
#
# All four exist now. The line still names what was actually run, because a
# QUIC-less host — one where `build-host-quic.sh` did not produce a bridge —
# runs the same script and covers two.
if [ ${#QUIC_ARGS[@]} -gt 0 ]; then
    echo "Carrier pairs: tcp/tcp, tcp/tcp+mux, udp/udp, udp/tcp and tcp/udp — all four, both directions of the split."
else
    echo "Carrier pairs: tcp/tcp only. No host QUIC bridge was built, so the other three were not compared."
fi

# The two numbers agreeing is not enough on its own: a harness that had stopped
# counting would report zero for both carriers and both clients, and the
# comparison above would call that agreement. So the dedicated case must come
# out at one connection per flow and the Mux case must come out below it —
# which is the claim in the plainest form it has.
EXPECTED_SHARDS=$(( (BURST_WIDTH + SHARD_DENSITY - 1) / SHARD_DENSITY ))
echo
printf 'Carriers for %d concurrent flows: oracle %s dedicated / %s mux, this client %s / %s\n' \
    "$BURST_WIDTH" "$ORACLE_DEDICATED_CARRIERS" "$ORACLE_MUX_CARRIERS" \
    "$OURS_DEDICATED_CARRIERS" "$OURS_MUX_CARRIERS"

for pair in "oracle:$ORACLE_DEDICATED_CARRIERS:$ORACLE_MUX_CARRIERS" \
            "this client:$OURS_DEDICATED_CARRIERS:$OURS_MUX_CARRIERS"; do
    who="${pair%%:*}"; rest="${pair#*:}"; dedicated="${rest%%:*}"; muxed="${rest##*:}"
    [ "$dedicated" -eq "$BURST_WIDTH" ] ||
        fail "$who used $dedicated connection(s) for $BURST_WIDTH unmultiplexed flows, not $BURST_WIDTH — the count is not measuring what it claims"
    [ "$muxed" -gt 0 ] && [ "$muxed" -lt "$dedicated" ] ||
        fail "$who used $muxed connection(s) at mux=1 against $dedicated at mux=0 — that is not multiplexing"
    [ "$muxed" -eq "$EXPECTED_SHARDS" ] ||
        fail "$who used $muxed shard(s) for $BURST_WIDTH flows; upstream states $SHARD_DENSITY active flows per shard, so $EXPECTED_SHARDS"
done
echo "OK  both implementations placed $BURST_WIDTH flows on $EXPECTED_SHARDS shards, at upstream's stated density of $SHARD_DENSITY"

echo
if [ "$DIVERGED" -eq 0 ]; then
    echo "PASS: both implementations behaved identically on every case"
    exit 0
fi
fail "$DIVERGED case(s) diverged from the oracle"
