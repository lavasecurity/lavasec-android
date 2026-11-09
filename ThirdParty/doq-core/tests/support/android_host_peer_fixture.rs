//! Exact public synthetic host-peer configuration and bounded failure metadata.
//! Shared by the executable fixture and its optimized production-engine regression.

pub(crate) const MAX_PACKET: usize = 1350;

pub(crate) fn query() -> Vec<u8> {
    let mut bytes = vec![0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    bytes.push(11); bytes.extend_from_slice(b"android-doq");
    bytes.push(7); bytes.extend_from_slice(b"invalid");
    bytes.extend_from_slice(&[0, 0, 1, 0, 1]);
    bytes
}

// Read only bounded numeric codes after a terminal fixture failure. Never print
// the BoringSSL error strings/data, which can include paths or peer material.
extern "C" { fn ERR_get_error() -> u32; }

pub(crate) fn send_failure(conn: &quiche::Connection, capacity: usize,
    error: quiche::Error) -> String {
    let stats = conn.stats();
    let mut crypto_errors = Vec::with_capacity(8);
    for _ in 0..8 {
        // Pinned vendored BoringSSL exports uint32_t ERR_get_error(void).
        let code = unsafe { ERR_get_error() };
        if code == 0 { break; }
        crypto_errors.push((code, code & 0xfff)); // ERR_GET_REASON from its public err.h.
    }
    format!("QUIC send: {error:?}; established={} draining={} closed={} local_error_present={} peer_error_present={} sent={} recv={} sent_bytes={} recv_bytes={} output_capacity={} crypto_errors={crypto_errors:?}",
        conn.is_established(), conn.is_draining(), conn.is_closed(), conn.local_error().is_some(),
        conn.peer_error().is_some(), stats.sent, stats.recv, stats.sent_bytes, stats.recv_bytes, capacity)
}

// The release regression imports this exact fixture configuration; it must not
// approximate the peer with quiche's different default UDP payload limits.
pub(crate) fn peer_config(certificate: &str, key: &str) -> Result<quiche::Config, String> {
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).map_err(|error| format!("QUIC config: {error:?}"))?;
    config.load_cert_chain_from_pem_file(certificate).map_err(|error| format!("test certificate: {error:?}"))?;
    config.load_priv_key_from_pem_file(key).map_err(|error| format!("test key: {error:?}"))?;
    config.set_application_protos(&[b"doq"]).map_err(|error| format!("DoQ ALPN: {error:?}"))?;
    config.set_max_idle_timeout(5000);
    config.set_max_recv_udp_payload_size(MAX_PACKET);
    config.set_max_send_udp_payload_size(MAX_PACKET);
    config.set_initial_max_data(8192);
    config.set_initial_max_stream_data_bidi_remote(8192);
    config.set_initial_max_streams_bidi(1);
    config.set_initial_max_streams_uni(0);
    config.set_disable_active_migration(true);
    config.enable_pacing(false);
    Ok(config)
}

