<!-- SPDX-License-Identifier: GPL-3.0-only -->
# The complete patch inventory

`ANYWHERE_PATCHES.md` in this directory is the donor's own document, left
byte-identical so that a future sync diffs cleanly. **It is incomplete**, and
this file records what it leaves out rather than editing it.

The donor's document describes two modifications and says the full set can be
found with:

```sh
grep -rn "Anywhere Patch" "app/src/main/jni/lwip/src"
```

That command finds **three** of **seven** patch sites. The markers were written
three different ways — `Anywhere Patch`, `Anywhere patch`, and `tun2socks
patch` — and the grep is case-sensitive. Use this instead:

```sh
grep -rniE "anywhere patch|tun2socks patch" app/src/main/jni/lwip/src
```

## The seven

| File | Line | Marker | What it does | Documented |
|---|---|---|---|---|
| `core/tcp_out.c` | 1266 | `Anywhere Patch` | Ignore the congestion window: the link to the kernel is memory, not a network | yes |
| `include/lwip/priv/tcp_priv.h` | 445 | `Anywhere Patch` | Disable delayed ACK | yes |
| `core/ipv4/icmp.c` | 216 | `Anywhere Patch` | Reset `if_idx` on pbuf reuse | **no** |
| `core/tcp_in.c` | 69 | `Anywhere patch` | `#ifndef` guard so `lwipopts.h` can override the initial window | **no** |
| `core/tcp_in.c` | 363 | `Anywhere patch` | Fall back to a wildcard listener with `local_port == 0` | **no** |
| `core/tcp_in.c` | 693 | `Anywhere patch` | Take the real destination port from the SYN when the listener is wildcard | **no** |
| `core/udp.c` | 313 | `tun2socks patch` | Fall back to a wildcard UDP pcb with `local_port == 0` | **no** |
| `core/ipv4/ip4.c` | 220 | `tun2socks patch` | Route through a netif with no address | **no** |
| `core/ipv4/ip4.c` | 418 | `tun2socks patch` | Accept every packet when the netif address is `0.0.0.0` | **no** |
| `core/ipv6/ip6.c` | 474 | `tun2socks patch` | Accept every packet when the first IPv6 address is `::` | **no** |

**The undocumented ones are the load-bearing ones.** Without the two catch-all
accepts and the two wildcard-pcb fallbacks, a TUN deployment does not work at
all: every packet is addressed to somewhere that is not this host, and there is
no listener on the port it wants. The two that *are* documented are performance
tuning. Someone reading only `ANYWHERE_PATCHES.md` would conclude this is
near-pristine lwIP with two speed tweaks, and would be wrong about the part that
matters.

## Why this matters for syncing

`internal/scripts/upstream-sync.sh` watches `app/src/main/jni/` in the donor.
When it reports movement, the delta to re-apply is the ten sites above, not the
two the donor's document lists.
