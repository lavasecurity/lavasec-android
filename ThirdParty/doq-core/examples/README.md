# Controlled Android DoQ socket peer

`android-doq-peer.rs` uses the pinned existing quiche dependency to receive one
real UDP/QUIC transaction. It binds **only `127.0.0.1:0`**, serves no arbitrary DNS
name, makes no outbound resolver query, and accepts only the exact zero-ID
`android-doq.invalid` A/IN question with client stream 0 and FIN. The real TLS
handshake must negotiate `doq` and present SNI `resolver.test`. Its framed,
zero-ID NXDOMAIN reply ends with FIN. The certificate/key are public synthetic
fixtures, not app or provider credentials.

Build on the same disposable Linux architecture as the emulator runner:

```sh
cd ThirdParty/doq-core
cargo build --locked --release --example android-doq-peer
target/release/examples/android-doq-peer \
  --cert tests/fixtures/resolver.crt --key tests/fixtures/resolver.key \
  --max-duration-ms 120000
```

All stdout lines are JSON, flushed immediately. Readiness appears only after
certificate loading and the actual UDP bind:

```json
{"event":"ready","address":"127.0.0.1","port":49152,"max_duration_ms":120000}
```

The port is chosen by the OS; this example number is not a fixed test port. Pass
the actual readiness port to a **separate, immediate** VPN instrumentation phase:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest \
  -e doqPeerHost 10.0.2.2 -e doqPeerPort "$DOQ_PEER_PORT" \
  com.lavasec.app.vpn.test/androidx.test.runner.AndroidJUnitRunner
```

The test identity is
`verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources`.
Missing/wrong peer configuration fails; it never skips or supplies a fake answer.
The general VPN suite must exclude this class once its separately executed
terminal result has been collected. The runner owns process launch, readiness
validation, source/artifact association, termination and fresh evidence files.

The peer emits `query` with exact synthetic framed query/reply hex, SNI, ALPN and
the **NAT-translated host-observed tuple**, then `complete` only after fully
writing the response flight and graceful close flight to UDP. The peer serves one
transaction and does not keep sending after the Android query worker releases its
session/socket. `complete` claims no ACK or Android acceptance; only the exact
native terminal result proves acceptance. Crypto errors and incomplete UDP writes
exit nonzero. The driver retains strict JSON stdout in `doq-peer.txt` and causal
stderr separately in `doq-peer-stderr.txt`; both the native pass and peer zero exit
remain mandatory. The whole process is bounded by the supplied
duration (1000..120000 ms, default 120000), even if no client arrives. No root,
privileged port, public TLS bypass or public provider is needed.

Android uses its actual packaged JNI engine, native verified test-CA trust,
logical hostname, exact captured ordinary `Network.bindSocket`, real UDP socket,
DNS validation and epoch registry. Socket-protection *authorization* is a named
controlled fixture boundary, as in the existing TLS peer tests. Its observed
peer is `10.0.2.2:port`; the host receipt must not be relabelled as an Android
physical source tuple. The test proves one canonical reply/ID restoration,
terminal native/socket release and no new work from the retired epoch. It does
not qualify shipping VPN consent/protect, TUN write, public resolver availability,
physical/OEM behavior, a complete A04 row or M3/F25.
