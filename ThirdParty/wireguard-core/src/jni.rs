//! JNI surface over the WireGuard engine C ABI, for the Android `:vpn` module.
//!
//! The Kotlin class is `com.lavasec.app.vpn.engine.NativeLavaWgCore`; its `external
//! fun`s bind to the `Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_*` symbols
//! below. This module is a thin marshalling layer — it contains no WireGuard logic:
//! it copies byte arrays across the JNI boundary, calls the proven `lava_wg_*`
//! functions, and encodes their `(op, peerAddress, payload)` result as one tagged
//! byte array.
//!
//! ## Queue confinement
//!
//! The C ABI's caller contract (one serial queue per session) still holds: the
//! Kotlin callers must confine every call for a handle to one dispatcher. JNI does
//! not add concurrency; it only narrows the call sites.
//!
//! ## Result encoding
//!
//! `encapsulate` / `decapsulate` / `tick` / `forceHandshake` return a `byte[]` of
//! `[tag][addrLen][addr...] ++ payload`, where `tag` is either an op code
//! ([`LAVA_WG_OP_NONE`]..[`LAVA_WG_OP_WRITE_TO_TUNNEL_V6`]) or a negative ABI error
//! code, and `addrLen` is 0, 4, or 16 (the decapsulated inner source address;
//! 0 for every other call). A zero-length payload with `tag == 0` is "nothing
//! produced". This keeps one JNI value shape for every outcome instead of throwing
//! across the boundary.
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;
use std::os::raw::c_void;

use crate::{
    lava_wg_decapsulate, lava_wg_encapsulate, lava_wg_force_handshake, lava_wg_session_free,
    lava_wg_session_new, lava_wg_session_stats, lava_wg_tick, LavaWGStats,
    LAVA_WG_ERR_INVALID_ARGUMENT, LAVA_WG_MAX_DATAGRAM, LAVA_WG_MAX_IP_PACKET,
    LAVA_WG_MIN_CONTROL_DST,
};

/// The X25519 key width the engine consumes unconditionally.
const KEY_BYTES: usize = 32;

/// A handle is a `Box<Session>` pointer widened to a `jlong`; 0 means "no session".
fn handle_of(pointer: *mut c_void) -> jlong {
    pointer as usize as jlong
}

/// Rebuilds the pointer from a Kotlin handle. Callers never pass 0 (checked first).
fn pointer_of(handle: jlong) -> *mut c_void {
    handle as usize as *mut c_void
}

/// `[tag][addrLen][addr...] ++ payload`, or `null` only on a JVM allocation failure.
/// `peer_address` is empty except for a decapsulation (`0`, `4`, or `16` bytes).
fn encode<'local>(
    env: &mut JNIEnv<'local>,
    tag: i32,
    peer_address: &[u8],
    payload: &[u8],
) -> jbyteArray {
    let address_length = peer_address.len();
    let mut out = Vec::with_capacity(2 + address_length + payload.len());
    out.push(tag as i8 as u8);
    out.push(address_length as u8);
    out.extend_from_slice(peer_address);
    out.extend_from_slice(payload);
    match env.byte_array_from_slice(&out) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Best-effort erase of a JNI-owned key copy. The C ABI scrubs its own copies and
/// expects the caller to erase theirs; without this the extra `Vec` copies would be
/// invisible to Kotlin and survive in allocator memory.
fn zeroize(buffer: &mut [u8]) {
    for byte in buffer.iter_mut() {
        unsafe { std::ptr::write_volatile(byte, 0) };
    }
    std::sync::atomic::compiler_fence(std::sync::atomic::Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_sessionNew<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    private_key: JByteArray<'local>,
    peer_public_key: JByteArray<'local>,
    preshared_key: JByteArray<'local>,
    keepalive_seconds: jint,
    index: jint,
) -> jlong {
    // Convert sequentially so every successfully created secret buffer is scrubbed if a
    // later conversion fails (a tuple `let ... else` would drop an already-converted
    // private-key Vec without zeroizing it).
    let Ok(mut private_bytes) = env.convert_byte_array(&private_key) else {
        return 0;
    };
    let Ok(peer_bytes) = env.convert_byte_array(&peer_public_key) else {
        zeroize(&mut private_bytes);
        return 0;
    };
    let Ok(mut psk_bytes) = env.convert_byte_array(&preshared_key) else {
        zeroize(&mut private_bytes);
        return 0;
    };
    if private_bytes.len() != KEY_BYTES || peer_bytes.len() != KEY_BYTES {
        zeroize(&mut private_bytes);
        zeroize(&mut psk_bytes);
        return 0;
    }
    // An empty PSK array means "none"; anything else must be exactly 32 bytes.
    let psk_pointer = match psk_bytes.len() {
        0 => std::ptr::null(),
        KEY_BYTES => psk_bytes.as_ptr(),
        _ => {
            zeroize(&mut private_bytes);
            zeroize(&mut psk_bytes);
            return 0;
        }
    };
    let session = unsafe {
        lava_wg_session_new(
            private_bytes.as_ptr(),
            peer_bytes.as_ptr(),
            psk_pointer,
            u16::try_from(keepalive_seconds).unwrap_or(0),
            index as u32,
        )
    };
    // The ABI copied what it needed; erase these JNI-owned copies on every exit.
    zeroize(&mut private_bytes);
    zeroize(&mut psk_bytes);
    handle_of(session)
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_sessionFree<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe { lava_wg_session_free(pointer_of(handle)) }
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_encapsulate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    packet: JByteArray<'local>,
) -> jbyteArray {
    if handle == 0 {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    }
    let Ok(source) = env.convert_byte_array(&packet) else {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    };
    if source.len() > LAVA_WG_MAX_IP_PACKET as usize {
        return encode(&mut env, crate::LAVA_WG_ERR_PACKET_TOO_LARGE, &[], &[]);
    }
    let mut destination = vec![0u8; LAVA_WG_MAX_DATAGRAM as usize];
    let mut out_len: u32 = 0;
    let op = unsafe {
        lava_wg_encapsulate(
            pointer_of(handle),
            source.as_ptr(),
            source.len() as u32,
            destination.as_mut_ptr(),
            destination.len() as u32,
            &mut out_len,
        )
    };
    finish(&mut env, op, &destination, out_len, &[])
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_decapsulate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    packet: JByteArray<'local>,
    source_address: JByteArray<'local>,
) -> jbyteArray {
    if handle == 0 {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    }
    let Ok(source) = env.convert_byte_array(&packet) else {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    };
    let Ok(peer_address) = env.convert_byte_array(&source_address) else {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    };
    if !matches!(peer_address.len(), 0 | 4 | 16) {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    }
    let mut destination = vec![0u8; LAVA_WG_MAX_DATAGRAM as usize];
    let mut out_len: u32 = 0;
    let mut out_peer_address = [0u8; 16];
    let mut out_peer_address_len: u32 = 0;
    let op = unsafe {
        lava_wg_decapsulate(
            pointer_of(handle),
            source.as_ptr(),
            source.len() as u32,
            peer_address.as_ptr(),
            peer_address.len() as u32,
            destination.as_mut_ptr(),
            destination.len() as u32,
            &mut out_len,
            out_peer_address.as_mut_ptr(),
            &mut out_peer_address_len,
        )
    };
    finish(&mut env, op, &destination, out_len, &out_peer_address[..out_peer_address_len as usize])
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    if handle == 0 {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    }
    let mut destination = vec![0u8; LAVA_WG_MAX_DATAGRAM as usize];
    let mut out_len: u32 = 0;
    let op = unsafe {
        lava_wg_tick(
            pointer_of(handle),
            destination.as_mut_ptr(),
            destination.len() as u32,
            &mut out_len,
        )
    };
    finish(&mut env, op, &destination, out_len, &[])
}

#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_forceHandshake<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    if handle == 0 {
        return encode(&mut env, LAVA_WG_ERR_INVALID_ARGUMENT, &[], &[]);
    }
    let mut destination = vec![0u8; LAVA_WG_MAX_DATAGRAM.max(LAVA_WG_MIN_CONTROL_DST) as usize];
    let mut out_len: u32 = 0;
    let op = unsafe {
        lava_wg_force_handshake(
            pointer_of(handle),
            destination.as_mut_ptr(),
            destination.len() as u32,
            &mut out_len,
        )
    };
    finish(&mut env, op, &destination, out_len, &[])
}

/// `[timeSinceLastHandshakeMs, txBytes, rxBytes, rttMs, lossRawFloatBits]`, or a
/// single-element `[error]` when the session is unusable.
#[no_mangle]
pub extern "system" fn Java_com_lavasec_app_vpn_engine_NativeLavaWgCore_sessionStats<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlongArray {
    if handle == 0 {
        return long_array(&mut env, &[LAVA_WG_ERR_INVALID_ARGUMENT as i64]);
    }
    let mut stats = LavaWGStats {
        time_since_last_handshake_ms: 0,
        tx_bytes: 0,
        rx_bytes: 0,
        estimated_loss: 0.0,
        estimated_rtt_ms: 0,
    };
    let op = unsafe { lava_wg_session_stats(pointer_of(handle), &mut stats) };
    if op < 0 {
        return long_array(&mut env, &[op as i64]);
    }
    long_array(
        &mut env,
        &[
            stats.time_since_last_handshake_ms,
            stats.tx_bytes as i64,
            stats.rx_bytes as i64,
            stats.estimated_rtt_ms as i64,
            stats.estimated_loss.to_bits() as i64,
        ],
    )
}

fn finish<'local>(
    env: &mut JNIEnv<'local>,
    op: i32,
    destination: &[u8],
    out_len: u32,
    peer_address: &[u8],
) -> jbyteArray {
    if op < 0 {
        encode(env, op, &[], &[])
    } else {
        let length = out_len as usize;
        let payload = if length <= destination.len() {
            &destination[..length]
        } else {
            &[]
        };
        encode(env, op, peer_address, payload)
    }
}

fn long_array<'local>(env: &mut JNIEnv<'local>, values: &[i64]) -> jlongArray {
    let Ok(array) = env.new_long_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}
