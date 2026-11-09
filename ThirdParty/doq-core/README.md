# Android DoQ packet engine

Pinned quiche 0.28.0 supplies QUIC/TLS 1.3 with its vendored BoringSSL. The JNI
adapter supplies no sockets or DNS bootstrap: the existing Android resolver owns
the ordinary-VPN-protected, exact-Network-bound UDP socket and the engine epoch.
All four Android ABIs use Rust 1.94.0, NDK 27.2 and 16 KB ELF LOAD alignment.

Each query currently owns one connection and client bidirectional stream 0.
Only ALPN `doq` is offered; 0-RTT and migration are disabled. Query and response
use the two-byte DNS length prefix and zero message IDs. A normal response is
accepted only after FIN and exactly one complete bounded DNS frame. The caller
ID is restored after validation. Cancellation attempts STOP_SENDING with
DOQ_REQUEST_CANCELLED before releasing a current socket; a retired generation
never sends new traffic. One absolute five-second deadline covers bootstrap,
handshake and response; an Android watchdog closes blocked I/O at that deadline.

Peer verification always uses trusted roots. Production passes Android OS CA
directories. quiche verifies DNS hostname/SNI; literal endpoints additionally
require an exact certificate IP SAN before sending DNS data. Controlled tests
can supply an explicit CA bundle without changing production verification.

`scripts/prepare-wireguard-core-android.sh` prepares both native engines.
`scripts/build-doq-core-android.sh` rebuilds this one library without deleting
other runtime libraries. The release bundle verifier requires the DoQ engine
for every supported ABI. QUICHE-LICENSE and BORINGSSL-LICENSE retain upstream
notices; the application Legal Notices inventory also includes this runtime.

Packaged-engine instrumentation proves JNI loading and trust-store availability,
not a successful public resolver query. Host transport tests and controlled
emulator TLS fixtures must accompany real network qualification.

The pin includes upstream [33cf86f2](https://github.com/cloudflare/quiche/commit/33cf86f2ac45814e4b3b01a985c07573990836aa),
which makes packet sealing mutable so LLVM preserves BoringSSL's nonce state.
The earlier optimized Linux peer failed with `CryptoFail` / `INVALID_NONCE`,
matching [upstream #2108](https://github.com/cloudflare/quiche/issues/2108).
The TLS-specific AEAD and nonce-order validation remain enabled.
Both native-engine and device-candidate CI run `cargo run --locked --release
--example crypto-release-regression`: 32 bounded, certificate-verified in-memory
QUIC exchanges using the shipping abort/LTO profile. The Rust test harness uses
unwinding even with `--release`, so its existing negative/transport tests remain
separate, as does the actual packaged Android UDP/JNI exchange. The old pin also
passes this executable on the local macOS compiler; that does not invalidate
the retained Linux failure or establish device qualification.
