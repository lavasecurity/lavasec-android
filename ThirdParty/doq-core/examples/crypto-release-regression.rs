//! Exercise the real TLS/QUIC cipher with the shipping panic=abort + LTO profile.
//! Rust's test harness uses unwind even for `cargo test --release`, which missed
//! quiche's BoringSSL aliasing bug. This executable never opens a network socket.
use std::net::SocketAddr;

#[path = "../tests/support/android_host_peer_fixture.rs"]
mod fixture;

fn flight(
    from: &mut quiche::Connection,
    to: &mut quiche::Connection,
    from_address: SocketAddr,
    to_address: SocketAddr,
) -> Result<(), String> {
    let mut packet = [0; fixture::MAX_PACKET];
    loop {
        match from.send(&mut packet) {
            Ok((size, _)) => match to.recv(
                &mut packet[..size],
                quiche::RecvInfo {
                    from: from_address,
                    to: to_address,
                },
            ) {
                Ok(_) | Err(quiche::Error::Done) => (),
                Err(error) => return Err(format!("receive: {error:?}")),
            },
            Err(quiche::Error::Done) => return Ok(()),
            Err(error) => return Err(fixture::send_failure(from, packet.len(), error)),
        }
    }
}

fn exchange() -> Result<(), String> {
    let root = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/");
    let certificate = format!("{root}resolver.crt");
    let key = format!("{root}resolver.key");
    let mut server_config = fixture::peer_config(&certificate, &key)?;
    let mut client_config = quiche::Config::new(quiche::PROTOCOL_VERSION).unwrap();
    client_config
        .load_verify_locations_from_file(&certificate)
        .unwrap();
    client_config.verify_peer(true);
    client_config.set_application_protos(&[b"doq"]).unwrap();
    client_config.set_max_recv_udp_payload_size(fixture::MAX_PACKET);
    client_config.set_max_send_udp_payload_size(fixture::MAX_PACKET);
    client_config.set_initial_max_data(8192);
    client_config.set_initial_max_stream_data_bidi_local(8192);
    client_config.enable_pacing(false);
    let client_address = "127.0.0.1:54321".parse().unwrap();
    let server_address = "127.0.0.1:853".parse().unwrap();
    let mut client = quiche::connect(
        Some("resolver.test"),
        &quiche::ConnectionId::from_ref(&[1; 16]),
        client_address,
        server_address,
        &mut client_config,
    )
    .unwrap();
    let mut server = quiche::accept(
        &quiche::ConnectionId::from_ref(&[2; 16]),
        None,
        server_address,
        client_address,
        &mut server_config,
    )
    .unwrap();
    let query = fixture::query();
    let mut expected = (query.len() as u16).to_be_bytes().to_vec();
    expected.extend_from_slice(&query);
    let mut answer = expected.clone();
    answer[4] = 0x81;
    answer[5] = 0x83;
    let mut posted = false;
    let mut received = Vec::new();
    let mut reply = Vec::new();
    for _ in 0..256 {
        flight(&mut client, &mut server, client_address, server_address)?;
        if server.is_established() {
            assert_eq!(server.application_proto(), b"doq");
            assert_eq!(server.server_name(), Some("resolver.test"));
            for stream in server.readable().collect::<Vec<_>>() {
                assert_eq!(stream, 0);
                let mut bytes = [0; 4096];
                let (size, fin) = server.stream_recv(stream, &mut bytes).unwrap();
                received.extend_from_slice(&bytes[..size]);
                if fin {
                    assert_eq!(received, expected);
                    assert_eq!(server.stream_send(0, &answer, true).unwrap(), answer.len());
                }
            }
        }
        flight(&mut server, &mut client, server_address, client_address)?;
        if client.is_established() && !posted {
            assert_eq!(client.application_proto(), b"doq");
            assert_eq!(
                client.stream_send(0, &expected, true).unwrap(),
                expected.len()
            );
            posted = true;
        }
        for stream in client.readable().collect::<Vec<_>>() {
            assert_eq!(stream, 0);
            let mut bytes = [0; 4096];
            let (size, fin) = client.stream_recv(stream, &mut bytes).unwrap();
            reply.extend_from_slice(&bytes[..size]);
            if fin {
                assert_eq!(reply, answer);
                return Ok(());
            }
        }
    }
    Err("the bounded verified exchange did not complete".into())
}

fn main() {
    assert!(cfg!(panic = "abort"), "run this executable with --release; the test harness cannot prove the shipping panic strategy");
    for index in 0..32 {
        if let Err(error) = exchange() {
            eprintln!("optimized cipher exchange {index} failed: {error}");
            std::process::exit(1);
        }
    }
    println!("32 verified DoQ cipher exchanges passed with panic=abort and release LTO");
}
