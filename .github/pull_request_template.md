## What this changes

<!-- One or two sentences. -->

## Protocol traceability

<!--
Delete this section if the change does not touch the protocol layer.
Otherwise: which section of the upstream docs/protocol.md does this implement or
correct? Every protocol implementation detail must be traceable to one.
-->

- Specification section:
- Baseline it was checked against:

## Checklist

- [ ] Protocol changes land with a matching known-answer or behavioural case
- [ ] `./gradlew ktlintCheck testDebugUnitTest koverVerifyDebug lintDebug` passes
- [ ] `python3 conformance/scripts/verify-vectors.py` passes
- [ ] No deployment-specific parameter is hardcoded — no ALPN value, padding
      curve, jitter parameter, derivation label or salt (V-05)
- [ ] No real node address, shared key or subscription token in the diff
