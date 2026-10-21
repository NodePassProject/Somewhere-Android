# Test certificates

Fixed certificates for `PeerVerificationTest`. Public test material only: no
private key is committed, and nothing here is trusted outside the test that
names it as an anchor.

| File | What it is |
|---|---|
| `ca.pem` | A test CA, used as the only trust anchor |
| `leaf.pem` | Signed by `ca.pem`. Subject CN `not-the-name.example`; SANs `portal.example`, `*.wild.example`, `192.0.2.1`, `2001:db8::1` |
| `self.pem` | Self-signed, CN and SAN `portal.example` — what a Portal's generated certificate looks like |

The CN of `leaf.pem` deliberately names a host that is not in its SANs, so a
verifier that fell back to the common name would be caught.

Valid for a century so that the tests do not start failing on a date. Made with
OpenSSL 3.5:

```sh
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca.key -out ca.pem \
  -days 36500 -subj "/CN=Somewhere Test CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout leaf.key -out leaf.csr \
  -subj "/CN=not-the-name.example"
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out leaf.pem -days 36500 \
  -extfile ext.cnf   # subjectAltName, basicConstraints=CA:FALSE, extendedKeyUsage=serverAuth
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout self.key -out self.pem \
  -days 36500 -subj "/CN=portal.example" -addext "subjectAltName=DNS:portal.example"
```
