These are public test-only TLS materials for the in-memory QUIC peer and the
source-controlled `examples/android-doq-peer.rs` real host UDP peer. The
self-signed resolver certificate has DNS SAN `resolver.test` and IP SAN
`192.0.2.53`. The unrelated certificate exercises untrusted-chain rejection.
They identify no application, service, account, or production signer. Tests pass
the resolver certificate explicitly as their trust root; production uses the
Android OS trust store. Never use these materials outside controlled tests.

`android/vpn/src/androidTest/assets/doq/resolver.crt` is a byte-identical copy of
this certificate for the separately executed Android JNI/socket test. Only the
public certificate is in the test APK; the synthetic private key remains with
the controlled host peer. Host in-memory tests still exchange packets in memory,
not UDP loopback.
