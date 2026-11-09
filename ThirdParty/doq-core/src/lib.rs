//! RFC 9250 client over quiche. This crate never creates a socket, resolves a name,
//! logs TLS secrets, or retains a session across the Android engine generation.
//! The caller supplies protected, Network-bound UDP I/O and owns the deadline.
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Mutex, OnceLock};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

const MAX_DNS: usize = 4096;
const MAX_PACKET: usize = 1350;
const CANCELLED: u64 = 3; // DOQ_REQUEST_CANCELLED, RFC 9250 section 4.3.
const PROTOCOL_ERROR: u64 = 2;
const INVALID: u8 = 255;
const TLS_FAILED: u8 = 254;

/// A bounded single-response frame. FIN is mandatory and extra messages fail closed.
#[derive(Default)]
struct ResponseFrame { bytes: Vec<u8>, finished: bool }
impl ResponseFrame {
    fn append(&mut self, bytes: &[u8], fin: bool) -> Result<(), ()> {
        if self.finished || self.bytes.len() + bytes.len() > MAX_DNS + 2 { return Err(()); }
        self.bytes.extend_from_slice(bytes);
        if self.bytes.len() >= 2 {
            let size = u16::from_be_bytes([self.bytes[0], self.bytes[1]]) as usize;
            if !(12..=MAX_DNS).contains(&size) || self.bytes.len() > size + 2 { return Err(()); }
            if fin && (self.bytes.len() != size + 2 || self.bytes[2] != 0 || self.bytes[3] != 0) {
                return Err(());
            }
        } else if fin { return Err(()); }
        self.finished = fin;
        Ok(())
    }
    fn answer(&self) -> Option<&[u8]> { self.finished.then(|| &self.bytes[2..]) }
}

struct Session {
    connection: quiche::Connection,
    local: SocketAddr,
    peer: SocketAddr,
    request: Vec<u8>,
    sent: usize,
    response: ResponseFrame,
    identity_approved: bool,
    identity_requested: bool,
    error: Option<u8>,
}
impl Session {
    fn new(host: &str, peer: SocketAddr, query: &[u8], trust: &str) -> Result<Self, ()> {
        if !(12..=MAX_DNS).contains(&query.len()) || query[..2] != [0, 0] || peer.port() == 53 {
            return Err(());
        }
        let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).map_err(|_| ())?;
        // Never fall back to an unverified handshake. Android supplies only OS trust
        // directories; controlled tests may instead supply an explicit CA bundle file.
        let mut roots = false;
        for location in trust.lines() {
            let loaded = match location.split_once(':') {
                Some(("dir", path)) => config.load_verify_locations_from_directory(path),
                Some(("file", path)) => config.load_verify_locations_from_file(path),
                _ => return Err(()),
            };
            loaded.map_err(|_| ())?;
            roots = true;
        }
        if !roots { return Err(()); }
        config.verify_peer(true);
        config.set_application_protos(&[b"doq"]).map_err(|_| ())?;
        config.set_max_idle_timeout(5000);
        config.set_max_recv_udp_payload_size(MAX_PACKET);
        config.set_max_send_udp_payload_size(MAX_PACKET);
        config.set_initial_max_data((MAX_DNS + 2) as u64);
        config.set_initial_max_stream_data_bidi_local((MAX_DNS + 2) as u64);
        config.set_initial_max_streams_bidi(0); // No server-initiated transactions.
        config.set_initial_max_streams_uni(0);
        config.set_disable_active_migration(true);
        config.enable_pacing(false); // Kotlin sends each emitted datagram immediately.
        let local: SocketAddr = if peer.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }.parse().map_err(|_| ())?;
        let mut cid = [0u8; 16];
        getrandom::getrandom(&mut cid).map_err(|_| ())?;
        let literal = host.parse::<IpAddr>().is_ok();
        // quiche validates DNS hostname/SNI itself. X509_check_host does not validate
        // IP SANs, so literal endpoints additionally require the caller's strict IP-SAN
        // check after the verified handshake and before any DNS application data.
        let connection = quiche::connect(
            if literal { None } else { Some(host) },
            &quiche::ConnectionId::from_ref(&cid), local, peer, &mut config,
        ).map_err(|_| ())?;
        let mut request = Vec::with_capacity(query.len() + 2);
        request.extend_from_slice(&(query.len() as u16).to_be_bytes());
        request.extend_from_slice(query);
        Ok(Self { connection, local, peer, request, sent: 0, response: ResponseFrame::default(),
            identity_approved: !literal, identity_requested: false, error: None })
    }
    fn fail(&mut self, error: u8) {
        self.error = Some(error);
        let _ = self.connection.close(true, PROTOCOL_ERROR, b"invalid DoQ response");
    }
    fn advance_stream(&mut self) {
        if !self.connection.is_established() || self.error.is_some() { return; }
        if self.connection.application_proto() != b"doq" { self.fail(TLS_FAILED); return; }
        if !self.identity_approved { return; }
        if self.sent < self.request.len() {
            match self.connection.stream_send(0, &self.request[self.sent..], true) {
                Ok(n) => self.sent += n,
                Err(quiche::Error::Done) => (),
                Err(_) => { self.fail(INVALID); return; }
            }
        }
        // A DoQ peer may only answer our client-initiated stream 0.
        let readable: Vec<_> = self.connection.readable().collect();
        for id in readable {
            if id != 0 { self.fail(INVALID); return; }
            let mut buffer = [0; MAX_DNS + 3];
            loop {
                match self.connection.stream_recv(id, &mut buffer) {
                    Ok((n, fin)) => {
                        if self.response.append(&buffer[..n], fin).is_err() { self.fail(INVALID); return; }
                        if fin { break; }
                    }
                    Err(quiche::Error::Done) => break,
                    Err(_) => { self.fail(INVALID); return; }
                }
            }
        }
    }
    /// Tagged JNI events: 0 idle, 1 UDP packet, 2 complete DNS response,
    /// 4 verified peer certificate requiring IP identity authorization, 254 TLS failure,
    /// 255 protocol/session failure. No answer is emitted before server FIN.
    fn next_event(&mut self) -> Vec<u8> {
        self.advance_stream();
        if let Some(error) = self.error { return vec![error]; }
        // A received CONNECTION_CLOSE starts QUIC draining; is_closed becomes true
        // only after its timer. Surface the already-authoritative fatal outcome now.
        // A complete response + FIN precedes a subsequent graceful/fatal peer close.
        if self.response.answer().is_none() {
            if let Some(error) = self.connection.local_error().or(self.connection.peer_error()) {
                let failure = if !self.connection.is_established() ||
                    (!error.is_app && (0x100..=0x1ff).contains(&error.error_code)) {
                    TLS_FAILED
                } else { INVALID };
                self.error = Some(failure);
                return vec![failure];
            }
        }
        if self.connection.is_established() && !self.identity_approved && !self.identity_requested {
            let Some(cert) = self.connection.peer_cert() else { self.fail(TLS_FAILED); return vec![TLS_FAILED]; };
            let mut event = vec![4]; event.extend_from_slice(cert);
            self.identity_requested = true;
            return event;
        }
        let mut packet = [0; MAX_PACKET];
        match self.connection.send(&mut packet) {
            Ok((n, info)) => {
                // Active migration is disabled. Never ask Android to send outside the
                // captured protected socket/peer, including a preferred-address change.
                if info.to != self.peer { self.fail(INVALID); return vec![INVALID]; }
                let mut event = vec![1]; event.extend_from_slice(&packet[..n]); return event;
            }
            Err(quiche::Error::Done) => (),
            Err(_) => { self.fail(INVALID); return vec![INVALID]; }
        }
        if let Some(answer) = self.response.answer() {
            let mut event = vec![2]; event.extend_from_slice(answer); return event;
        }
        if self.connection.is_closed() {
            let error = if !self.connection.is_established() { TLS_FAILED } else { INVALID };
            return vec![error];
        }
        vec![0]
    }
    fn receive(&mut self, packet: &mut [u8]) -> jint {
        match self.connection.recv(packet, quiche::RecvInfo { from: self.peer, to: self.local }) {
            Ok(_) | Err(quiche::Error::Done) => 0,
            Err(quiche::Error::TlsFail) => { self.fail(TLS_FAILED); -2 },
            // Invalid UDP packets are discarded by the QUIC engine, as required by
            // QUIC. The outer absolute deadline bounds a stream of such packets.
            Err(_) => 0,
        }
    }
    fn cancel(&mut self) {
        let _ = self.connection.stream_shutdown(0, quiche::Shutdown::Read, CANCELLED);
        let _ = self.connection.stream_shutdown(0, quiche::Shutdown::Write, CANCELLED);
    }
}

#[derive(Default)]
struct Sessions { next: jlong, values: HashMap<jlong, Session> }
fn sessions() -> &'static Mutex<Sessions> { static ALL: OnceLock<Mutex<Sessions>> = OnceLock::new(); ALL.get_or_init(Mutex::default) }
fn bytes(env: &mut JNIEnv<'_>, value: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(value).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_create(
    mut env: JNIEnv, _: JClass, host: JString, peer: JString, query: JByteArray, trust: JString,
) -> jlong {
    let Ok(host) = env.get_string(&host).map(String::from) else { return 0; };
    let Ok(peer) = env.get_string(&peer).map(String::from) else { return 0; };
    let Ok(trust) = env.get_string(&trust).map(String::from) else { return 0; };
    let Ok(query) = env.convert_byte_array(&query) else { return 0; };
    let Ok(peer) = peer.parse() else { return 0; };
    let Ok(session) = Session::new(&host, peer, &query, &trust) else { return 0; };
    let Ok(mut all) = sessions().lock() else { return 0; };
    let Some(next) = all.next.checked_add(1) else { return 0; };
    all.next = next; all.values.insert(next, session); next
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_nextEvent(mut env: JNIEnv, _: JClass, handle: jlong) -> jbyteArray {
    let event = sessions().lock().ok().and_then(|mut all| all.values.get_mut(&handle).map(Session::next_event)).unwrap_or_else(|| vec![INVALID]);
    bytes(&mut env, &event)
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_receive(env: JNIEnv, _: JClass, handle: jlong, packet: JByteArray) -> jint {
    let Ok(mut packet) = env.convert_byte_array(&packet) else { return -1; };
    if packet.len() > 65535 { return -1; }
    sessions().lock().ok().and_then(|mut all| all.values.get_mut(&handle).map(|s| s.receive(&mut packet))).unwrap_or(-1)
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_timeoutMillis(_: JNIEnv, _: JClass, handle: jlong) -> jlong {
    sessions().lock().ok().and_then(|all| all.values.get(&handle).map(|s| s.connection.timeout().map(|d| d.as_millis().min(100) as jlong).unwrap_or(100))).unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_onTimeout(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(mut all) = sessions().lock() { if let Some(s) = all.values.get_mut(&handle) { s.connection.on_timeout(); } }
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_approveIpIdentity(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(mut all) = sessions().lock() { if let Some(s) = all.values.get_mut(&handle) { s.identity_approved = true; } }
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_cancel(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(mut all) = sessions().lock() { if let Some(s) = all.values.get_mut(&handle) { s.cancel(); } }
}
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_NativeDoqCore_free(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(mut all) = sessions().lock() { all.values.remove(&handle); }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn split_frame_requires_exact_length_zero_id_and_fin() {
        let mut frame = ResponseFrame::default();
        frame.append(&[0], false).unwrap();
        frame.append(&[12, 0, 0, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0], false).unwrap();
        assert!(frame.answer().is_none());
        frame.append(&[], true).unwrap();
        assert_eq!(frame.answer().unwrap().len(), 12);
        assert!(frame.append(&[], true).is_err());
    }
    #[test]
    fn rejects_extra_message_truncation_nonzero_id_and_oversize() {
        for bytes in [vec![0, 12, 0], vec![0, 12, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            vec![0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], vec![0x10, 1]] {
            assert!(ResponseFrame::default().append(&bytes, true).is_err());
        }
    }
}

#[cfg(test)]
mod transport_tests;
