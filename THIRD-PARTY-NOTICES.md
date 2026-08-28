# Third-party notices

Somewhere is licensed under GPL-3.0-only. Some files distributed with it are
not: they carry their own licence, and are listed here because the binary ships
them.

## Bundled fonts

Both families are under the **SIL Open Font License, Version 1.1**, which is
compatible with GPL-3.0 and permits bundling in an application. The full licence
text for each is in [`licences/`](licences/).

| Family | Files | Copyright | Licence |
|---|---|---|---|
| Archivo | `app/src/main/res/font/archivo_variable.ttf` | © 2020 The Archivo Project Authors | [OFL-1.1](licences/OFL-1.1-Archivo.txt) |
| IBM Plex Sans | `app/src/main/res/font/plex_sans_variable.ttf` | © 2017 IBM Corp., Reserved Font Name "Plex" | [OFL-1.1](licences/OFL-1.1-IBMPlex.txt) |
| IBM Plex Mono | `app/src/main/res/font/plex_mono_{regular,medium}.ttf` | © 2017 IBM Corp., Reserved Font Name "Plex" | [OFL-1.1](licences/OFL-1.1-IBMPlex.txt) |

The OFL's Reserved Font Name clause means the files must not be redistributed
under the name "Plex" if modified. They are shipped unmodified, under their
original names.

## Vendored C — lwIP

`app/src/main/jni/lwip/` is a copy of the **lwIP** TCP/IP stack, which is under
a **three-clause BSD licence** — compatible with GPL-3.0, and the reason the
donor could vendor it in the first place. Full text:
[`licences/BSD-3-Clause-lwIP.txt`](licences/BSD-3-Clause-lwIP.txt).

The BSD licence's second condition requires that a **binary** redistribution
reproduce the copyright notice, which is what this section is for: the APK
ships `libsomewhere_native.so` and lwIP is compiled into it.

| Component | Files | Copyright | Licence |
|---|---|---|---|
| lwIP | `app/src/main/jni/lwip/src/**` | © 2001-2004 Swedish Institute of Computer Science, and the individual holders each file names | [BSD-3-Clause](licences/BSD-3-Clause-lwIP.txt) |

**The copy is modified.** Ten sites differ from pristine lwIP, all inherited
from the donor rather than made here; they are listed in
[`app/src/main/jni/lwip/INHERITED-PATCHES.md`](app/src/main/jni/lwip/INHERITED-PATCHES.md).
Two headers in the tree — `lwip/priv/memp_std.h` and `netif/etharp.h` — carry no
notice of their own; both are upstream lwIP files that lack one upstream, not
files stripped here.

The bridge and port files beside it (`lwip_bridge.c`, `lwip_jni_bridge.c`,
`port/`, `compat/`) are **not** lwIP: they come from
`NodePassProject/Anywhere-Android` at `e9a9274` and are GPL-3.0 like this
project. Each names its origin in its own header.

## The QUIC stack — ngtcp2 and aws-lc

`libsomewhere_native.so` statically links a QUIC transport and the TLS library
underneath it. Neither is committed to this repository: they are fetched at
pinned commits and built by [`tools/quic/build-deps.sh`](tools/quic/build-deps.sh)
from the revisions in [`tools/quic/DEPENDENCIES`](tools/quic/DEPENDENCIES),
which is D-17. **They still ship**, compiled into the shared object, so they are
listed here for the same reason lwIP is.

| Component | Version | Copyright | Licence |
|---|---|---|---|
| ngtcp2 | v1.17.0 (`01b05ba1`) | © 2016 ngtcp2 contributors | [MIT](licences/MIT-ngtcp2.txt) |
| aws-lc | v1.68.0 (`06281908`) | © Amazon.com, Inc. or its affiliates, and the BoringSSL and OpenSSL holders each file names | [Apache-2.0 OR ISC, over BoringSSL and OpenSSL terms](licences/aws-lc-LICENSE.txt), [NOTICE](licences/aws-lc-NOTICE.txt) |

aws-lc is a fork of BoringSSL, itself a fork of OpenSSL, and its `LICENSE`
carries all three sets of terms. The file is reproduced whole rather than
summarised, because which terms apply depends on which file a given object came
from and that is not a judgement this notice should be making.

**Neither is patched.** What is deliberately not taken — aws-lc's tests and
command-line tool, ngtcp2's examples and its second crypto backend — is recorded
in `DEPENDENCIES` beside the pins.

**Why a TLS library is carried at all**, when this project declined BLAKE3 and
libyaml on review cost: NW-P-01 authenticates a QUIC connection with an RFC 5705
exporter from its TLS 1.3 handshake, and no option satisfying that avoids one.
The two declined libraries served a protocol this client does not speak; this
one is load-bearing.

**Corresponding source.** These are statically linked into a GPL-3.0 binary, so
a distributed build must be accompanied by the source it was built from.
`tools/quic/build-deps.sh --bundle-source` produces that archive from the
verified trees.

## Icons

The icon set in `app/src/main/kotlin/eu/nodepass/somewhere/ui/icons/` is drawn
from [Lucide](https://lucide.dev) path data, which is ISC-licensed. The vectors
are rebuilt in Kotlin rather than vendored, so no Lucide file ships; the path
geometry is acknowledged here.

## Runtime dependencies

Declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). AndroidX
and Kotlin libraries are Apache-2.0; Conscrypt is Apache-2.0. None are bundled
in source form.
