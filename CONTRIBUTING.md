# Contributing

## Before protocol code

```sh
internal-only: upstream-sync.sh                     # if you have the workbench
conformance/scripts/drift-check.sh                  # has the specification moved?
```

The protocol baseline is **pinned**, not tracked. `conformance/PROTOCOL_BASELINE`
names the upstream commit this client implements, and a scheduled CI job opens an
issue when upstream makes a normative change. Advancing the baseline is a
deliberate act with a matching conformance update, never a side effect.

## The gates

```sh
./gradlew ktlintCheck testDebugUnitTest koverVerifyDebug lintDebug \
          checkClasspathConsistency assembleDebug
python3 conformance/scripts/verify-vectors.py
```

All of them run in CI on every pull request. Two more are worth running locally
when you touch what they cover:

```sh
eval "$(conformance/scripts/portal-for-tests.sh)"   # the live-Portal tests
./gradlew testDebugUnitTest
conformance/scripts/portal-for-tests.sh --stop

./gradlew connectedDebugAndroidTest                 # the design rules, on a device
```

## What a change is expected to carry

- **Protocol code lands with a known-answer or behavioural case.** Traceable to
  a specific section of the upstream `docs/protocol.md`.
- **A rule that a document states, a test asserts.** Every contrast figure in
  `docs/design-system.md` and every i18n rule in `docs/i18n.md` is enforced
  rather than described, because measuring has caught defects that reading did
  not.
- **A gate that can silently pass is verified to fail.** The coverage gate, the
  classpath gate and the design-rule suite were each broken on purpose once to
  confirm they notice.

## Things that are easy to get wrong

`CLAUDE.md` carries a numbered list of them — verified facts about the protocol,
the platform and the donor project, each of which cost somebody an afternoon.
Read it before assuming any of them.

## Never in a commit

This repository is public from its first commit and history cannot really be
deleted.

- No real node address, subscription URL, or shared key. `example.net` and
  RFC 5737 addresses exist for this.
- No deployment-specific protocol parameter — ALPN values, padding curves,
  jitter parameters, key derivation labels or salts. The client implements
  mechanisms; parameters are delivered at runtime.
- Nothing that identifies a person or a deployment.

## Security

Do not open a public issue for a security defect. See [SECURITY.md](SECURITY.md).
