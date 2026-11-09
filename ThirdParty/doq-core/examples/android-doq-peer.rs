//! Public synthetic fixture only. A real UDP/quiche peer for the Android JNI client;
//! it is neither a resolver nor a production transport and never forwards a query.
use std::env;
use std::io::{self, Write};
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

#[path = "../tests/support/android_host_peer_fixture.rs"]
mod fixture;
use fixture::{peer_config, query, send_failure, MAX_PACKET};
const MAX_DURATION_MS: u64 = 120_000;

struct Arguments { certificate: String, key: String, duration: Duration }

fn arguments() -> Result<Arguments, String> {
    let mut certificate = None;
    let mut key = None;
    let mut duration = MAX_DURATION_MS;
    let mut args = env::args().skip(1);
    while let Some(flag) = args.next() {
        let value = args.next().ok_or_else(|| format!("missing value for {flag}"))?;
        match flag.as_str() {
            "--cert" if certificate.is_none() => certificate = Some(value),
            "--key" if key.is_none() => key = Some(value),
            "--max-duration-ms" => duration = value.parse().map_err(|_| "invalid duration")?,
            _ => return Err(format!("unknown or duplicate argument {flag}")),
        }
    }
    if !(1000..=MAX_DURATION_MS).contains(&duration) { return Err("duration must be 1000..120000 ms".into()); }
    Ok(Arguments {
        certificate: certificate.ok_or("--cert is required")?,
        key: key.ok_or("--key is required")?,
        duration: Duration::from_millis(duration),
    })
}

fn hex(bytes: &[u8]) -> String { bytes.iter().map(|byte| format!("{byte:02x}")).collect() }

fn emit(line: String) -> Result<(), String> {
    println!("{line}");
    io::stdout().flush().map_err(|error| format!("cannot flush peer receipt: {error}"))
}

// Drain every currently sendable packet and require the complete UDP write for
// each. A queued stream frame alone is never a host completion receipt.
fn send_flight(conn: &mut quiche::Connection, socket: &UdpSocket,
    outgoing: &mut [u8], peer: SocketAddr) -> Result<usize, String> {
    let mut datagrams = 0;
    loop {
        match conn.send(outgoing) {
            Ok((size, info)) => {
                if info.to != peer { return Err("QUIC attempted a different peer".into()); }
                let sent = socket.send_to(&outgoing[..size], info.to).map_err(|error| format!("UDP send: {error}"))?;
                if sent != size { return Err("partial UDP datagram".into()); }
                datagrams += 1;
            }
            Err(quiche::Error::Done) => return Ok(datagrams),
            Err(error) => return Err(send_failure(conn, outgoing.len(), error)),
        }
    }
}

fn run(args: Arguments) -> Result<(), String> {
    let mut config = peer_config(&args.certificate, &args.key)?;

    // The host alias 10.0.2.2 reaches this host-loopback socket from the owned emulator.
    // The host-observed source below is NAT-translated, never an Android physical tuple.
    let socket = UdpSocket::bind("127.0.0.1:0").map_err(|error| format!("loopback bind: {error}"))?;
    socket.set_write_timeout(Some(Duration::from_millis(50))).map_err(|error| format!("UDP write deadline: {error}"))?;
    let local = socket.local_addr().map_err(|error| format!("local address: {error}"))?;
    let deadline = Instant::now() + args.duration;
    emit(format!("{{\"event\":\"ready\",\"address\":\"127.0.0.1\",\"port\":{},\"max_duration_ms\":{}}}", local.port(), args.duration.as_millis()))?;
    let mut connection: Option<quiche::Connection> = None;
    let mut captured_peer: Option<SocketAddr> = None;
    let mut received_frame = Vec::new();
    let mut replied = false;
    let mut incoming = [0; 65535];
    let mut outgoing = [0; MAX_PACKET];
    loop {
        let now = Instant::now();
        if now >= deadline { return Err("peer deadline expired without completing the one controlled exchange".into()); }
        let wait = connection.as_ref().and_then(|conn| conn.timeout()).unwrap_or(Duration::from_millis(50))
            .min(Duration::from_millis(50)).min(deadline - now).max(Duration::from_millis(1));
        socket.set_read_timeout(Some(wait)).map_err(|error| format!("read deadline: {error}"))?;
        match socket.recv_from(&mut incoming) {
            Ok((size, from)) => {
                if connection.is_none() {
                    if !from.ip().is_loopback() { return Err("host peer received a non-loopback source".into()); }
                    let header = quiche::Header::from_slice(&mut incoming[..size], quiche::MAX_CONN_ID_LEN)
                        .map_err(|error| format!("initial QUIC header: {error:?}"))?;
                    if header.ty != quiche::Type::Initial || !quiche::version_is_supported(header.version) {
                        return Err("peer requires a supported QUIC Initial".into());
                    }
                    let mut id = [0; 16];
                    getrandom::getrandom(&mut id).map_err(|_| "connection id generation failed")?;
                    connection = Some(quiche::accept(&quiche::ConnectionId::from_ref(&id), None,
                        local, from, &mut config).map_err(|error| format!("QUIC accept: {error:?}"))?);
                    captured_peer = Some(from);
                }
                if captured_peer != Some(from) { continue; }
                let conn = connection.as_mut().unwrap();
                match conn.recv(&mut incoming[..size], quiche::RecvInfo { from, to: local }) {
                    Ok(_) | Err(quiche::Error::Done) => (),
                    Err(error) => return Err(format!("QUIC receive: {error:?}")),
                }
            }
            Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => (),
            Err(error) => return Err(format!("UDP receive: {error}")),
        }
        let Some(conn) = connection.as_mut() else { continue; };
        if conn.timeout().is_some_and(|timeout| timeout.is_zero()) { conn.on_timeout(); }
        if conn.is_established() {
            if conn.application_proto() != b"doq" { return Err("negotiated protocol is not DoQ".into()); }
            if conn.server_name() != Some("resolver.test") { return Err("TLS SNI is not resolver.test".into()); }
            let streams: Vec<_> = conn.readable().collect();
            for stream in streams {
                if stream != 0 || replied { return Err("unexpected additional DoQ transaction".into()); }
                let mut bytes = [0; 4099];
                loop {
                    match conn.stream_recv(stream, &mut bytes) {
                        Ok((size, fin)) => {
                            if received_frame.len() + size > 4098 { return Err("oversized DNS frame".into()); }
                            received_frame.extend_from_slice(&bytes[..size]);
                            if !fin { continue; }
                            let expected = query();
                            let mut expected_frame = (expected.len() as u16).to_be_bytes().to_vec();
                            expected_frame.extend_from_slice(&expected);
                            if received_frame != expected_frame { return Err("query differs from the exact zero-ID synthetic DNS frame".into()); }
                            let mut answer = expected;
                            answer[2] = 0x81; answer[3] = 0x83; // QR/RD/RA, NXDOMAIN; no Internet lookup.
                            let mut reply = (answer.len() as u16).to_be_bytes().to_vec();
                            reply.extend_from_slice(&answer);
                            let count = conn.stream_send(0, &reply, true).map_err(|error| format!("response frame: {error:?}"))?;
                            if count != reply.len() { return Err("partial response queue".into()); }
                            replied = true;
                            emit(format!("{{\"event\":\"query\",\"alpn\":\"doq\",\"sni\":\"resolver.test\",\"stream\":0,\"query_fin\":true,\"query_wire_hex\":\"{}\",\"reply_wire_hex\":\"{}\",\"host_observed_peer\":\"{}\",\"source_scope\":\"NAT-translated-host-loopback\"}}",
                                hex(&received_frame), hex(&reply), captured_peer.unwrap()))?;
                            break;
                        }
                        Err(quiche::Error::Done) => break,
                        Err(error) => return Err(format!("DNS stream: {error:?}")),
                    }
                }
            }
        }
        let datagrams = send_flight(conn, &socket, &mut outgoing, captured_peer.unwrap())?;
        if replied {
            if datagrams == 0 { return Err("response queued without a complete UDP flight".into()); }
            if conn.local_error().is_some() || conn.peer_error().is_some() {
                return Err("QUIC/TLS failed while writing the exact DNS response".into());
            }
            // This peer serves exactly one transaction. The Android query worker
            // releases its session/socket after the FIN response, so do not keep
            // the server open for arbitrary post-response PTO retransmissions.
            // Close only after draining the complete response flight to UDP; then
            // fully write the graceful close flight. No ACK receipt is claimed.
            conn.close(true, 0, b"controlled response complete")
                .map_err(|error| format!("graceful peer close: {error:?}"))?;
            if send_flight(conn, &socket, &mut outgoing, captured_peer.unwrap())? == 0 {
                return Err("graceful close was not written to UDP".into());
            }
            emit("{\"event\":\"complete\",\"accepted_queries\":1,\"response_queued_with_fin\":true,\"scope\":\"host-loopback-NAT-peer\"}".into())?;
            return Ok(());
        }
        if !replied && (conn.is_closed() || conn.is_draining() || conn.local_error().is_some() || conn.peer_error().is_some()) {
            return Err("QUIC/TLS closed before the exact DNS transaction".into());
        }
    }
}

fn main() {
    if let Err(error) = arguments().and_then(run) {
        eprintln!("android-doq-peer: {error}");
        std::process::exit(1);
    }
}
