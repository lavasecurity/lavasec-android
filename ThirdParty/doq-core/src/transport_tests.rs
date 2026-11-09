//! Controlled real QUIC/TLS peers exchanging packets in memory. No UDP socket or
//! external resolver participates, and the production client verification stays enabled.
use super::*;

// Keep the executable fixture's actual ALPN, certificate and UDP limits in
// this regression without importing its CLI/socket/main behavior.
#[path = "../tests/support/android_host_peer_fixture.rs"]
mod android_peer;

fn fixture(name: &str) -> String {
    format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"))
}
fn query() -> Vec<u8> {
    vec![0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, b'a', 0, 0, 1, 0, 1]
}
fn server(client: &Session, alpn: &[u8]) -> quiche::Connection {
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).unwrap();
    config.load_cert_chain_from_pem_file(&fixture("resolver.crt")).unwrap();
    config.load_priv_key_from_pem_file(&fixture("resolver.key")).unwrap();
    config.set_application_protos(&[alpn]).unwrap();
    config.set_max_idle_timeout(5000);
    config.set_initial_max_data(8192);
    config.set_initial_max_stream_data_bidi_remote(8192);
    config.set_initial_max_streams_bidi(1);
    config.set_initial_max_streams_uni(0);
    config.enable_pacing(false);
    quiche::accept(&quiche::ConnectionId::from_ref(&[42; 16]), None,
        client.peer, client.local, &mut config).unwrap()
}

/// Drives the actual production packet engine and a local quiche server. The
/// response is split over two writes, and the client must wait for the second FIN.
fn exchange(host: &str, trust: &str, alpn: &[u8], malformed: bool, approve_literal: bool) -> (Vec<u8>, Vec<u8>, bool) {
    let mut client = Session::new(host, "192.0.2.53:853".parse().unwrap(), &query(),
        &format!("file:{}", fixture(trust))).unwrap();
    let mut server = server(&client, alpn);
    let mut captured = Vec::new();
    let mut response = Vec::new();
    let mut response_started = false;
    let mut response_finished = false;
    let mut saw_literal_identity = false;
    for _ in 0..200 {
        loop {
            let mut event = client.next_event();
            match event[0] {
                0 => break,
                1 => {
                    let _ = server.recv(&mut event[1..], quiche::RecvInfo { from: client.local, to: client.peer });
                }
                4 => {
                    saw_literal_identity = true;
                    assert!(!event[1..].is_empty());
                    assert!(captured.is_empty(), "IP identity must precede DNS application bytes");
                    if approve_literal { client.identity_approved = true; } else { return (event, captured, true); }
                }
                _ => return (event, captured, saw_literal_identity),
            }
        }
        if server.is_established() {
            let ids: Vec<_> = server.readable().collect();
            for id in ids {
                assert_eq!(id, 0, "transaction uses the client bidirectional stream");
                let mut incoming = [0; MAX_DNS + 2];
                while let Ok((n, fin)) = server.stream_recv(id, &mut incoming) {
                    captured.extend_from_slice(&incoming[..n]);
                    if fin {
                        assert_eq!(&captured[2..], query());
                        response = captured.clone();
                        response[4] |= 0x80; // DNS response QR bit, after the two-byte prefix.
                        if malformed { response[2] = 1; } // RFC 9250 requires response ID zero.
                        server.stream_send(0, &response[..3], false).unwrap();
                        response_started = true;
                        break;
                    }
                }
            }
        }
        let mut outgoing = [0; MAX_PACKET];
        while let Ok((n, _)) = server.send(&mut outgoing) { client.receive(&mut outgoing[..n]); }
        if response_started && !response_finished {
            assert!(client.response.answer().is_none(), "a prefix without FIN is not an answer");
            server.stream_send(0, &response[3..], true).unwrap();
            response_finished = true;
        }
    }
    panic!("controlled transport did not reach a terminal event: host={host} trust={trust} alpn={alpn:?} client_closed={} client_peer_error={:?} client_local_error={:?} server_local_error={:?}",
        client.connection.is_closed(), client.connection.peer_error(), client.connection.local_error(), server.local_error());
}

#[test]
fn verified_doq_handshake_round_trips_framed_query_and_waits_for_fin() {
    let (event, captured, literal) = exchange("resolver.test", "resolver.crt", b"doq", false, false);
    assert_eq!(event[0], 2);
    assert_eq!(&captured[..2], &(query().len() as u16).to_be_bytes());
    assert_eq!(&event[1..3], &[0, 0]);
    assert_eq!(&event[13..], &query()[12..]);
    assert!(!literal);
}

#[test]
fn certificate_chain_hostname_and_alpn_fail_closed_before_dns() {
    for (host, trust, alpn) in [
        ("wrong.test", "resolver.crt", &b"doq"[..]),
        ("resolver.test", "untrusted.crt", &b"doq"[..]),
        ("resolver.test", "resolver.crt", &b"h3"[..]),
    ] {
        let (event, captured, _) = exchange(host, trust, alpn, false, false);
        assert_eq!(event[0], TLS_FAILED, "{host}/{trust}/{alpn:?}");
        assert!(captured.is_empty(), "identity failure must not disclose a DNS query");
    }
}

#[test]
fn ip_literal_waits_for_verified_certificate_identity_approval() {
    let (event, captured, literal) = exchange("192.0.2.53", "resolver.crt", b"doq", false, false);
    assert_eq!(event[0], 4);
    assert!(captured.is_empty());
    assert!(literal);
    let (event, _, literal) = exchange("192.0.2.53", "resolver.crt", b"doq", false, true);
    assert_eq!(event[0], 2);
    assert!(literal);
}

#[test]
fn nonzero_response_id_is_a_protocol_error_after_verified_handshake() {
    let (event, _, _) = exchange("resolver.test", "resolver.crt", b"doq", true, false);
    assert_eq!(event[0], INVALID);
}

#[test]
fn android_host_peer_configuration_round_trips_exact_android_query() {
    assert_eq!(MAX_PACKET, android_peer::MAX_PACKET);
    let posted = android_peer::query();
    let mut client = Session::new("resolver.test", "127.0.0.1:853".parse().unwrap(),
        &posted, &format!("file:{}", fixture("resolver.crt"))).unwrap();
    let mut config = android_peer::peer_config(&fixture("resolver.crt"),
        &fixture("resolver.key")).unwrap();
    let mut server_id = [0; 16];
    getrandom::getrandom(&mut server_id).unwrap();
    let mut server = quiche::accept(&quiche::ConnectionId::from_ref(&server_id), None,
        client.peer, client.local, &mut config).unwrap();
    let mut captured = Vec::new();
    let mut expected = (posted.len() as u16).to_be_bytes().to_vec();
    expected.extend_from_slice(&posted);
    let mut reply = expected.clone();
    reply[4] = 0x81;
    reply[5] = 0x83;
    let mut response_queued = false;
    for _ in 0..200 {
        // A single transaction, with bounded in-memory turns and no retry/reset
        // of a failed connection. Every real engine terminal failure is fatal.
        loop {
            let mut event = client.next_event();
            match event[0] {
                0 => break,
                1 => {
                    assert!(event.len() <= MAX_PACKET + 1);
                    match server.recv(&mut event[1..], quiche::RecvInfo { from: client.local, to: client.peer }) {
                        Ok(_) | Err(quiche::Error::Done) => (),
                        Err(error) => panic!("controlled host receive failed: {error:?}"),
                    }
                },
                2 => {
                    assert!(response_queued);
                    assert_eq!(captured, expected);
                    assert_eq!(&event[1..], &reply[2..]);
                    assert!(server.stats().recv > 0 && server.stats().sent > 0);
                    return;
                },
                _ => panic!("production client failed exact host exchange: event={} established={} sent={} recv={}",
                    event[0], client.connection.is_established(), client.connection.stats().sent,
                    client.connection.stats().recv),
            }
        }
        if server.is_established() {
            assert_eq!(server.application_proto(), b"doq");
            assert_eq!(server.server_name(), Some("resolver.test"));
            for stream in server.readable().collect::<Vec<_>>() {
                assert_eq!(stream, 0);
                assert!(!response_queued, "the fixture accepts only one query");
                let mut buffer = [0; MAX_DNS + 2];
                loop {
                    match server.stream_recv(stream, &mut buffer) {
                        Ok((size, fin)) => {
                            captured.extend_from_slice(&buffer[..size]);
                            assert!(captured.len() <= expected.len());
                            if fin {
                                assert_eq!(captured, expected);
                                assert_eq!(server.stream_send(0, &reply, true).unwrap(), reply.len());
                                response_queued = true;
                                break;
                            }
                        },
                        Err(quiche::Error::Done) => break,
                        Err(error) => panic!("controlled host stream failed: {error:?}"),
                    }
                }
            }
        }
        let mut outgoing = [0; MAX_PACKET];
        loop {
            match server.send(&mut outgoing) {
                Ok((size, info)) => {
                    assert_eq!(info.to, client.local);
                    assert!(size <= MAX_PACKET);
                    assert_eq!(client.receive(&mut outgoing[..size]), 0);
                },
                Err(quiche::Error::Done) => break,
                Err(error) => panic!("{}", android_peer::send_failure(&server, outgoing.len(), error)),
            }
        }
    }
    panic!("exact host configuration did not complete its one bounded exchange");
}
