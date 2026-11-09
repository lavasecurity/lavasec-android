package com.lavasec.app.reactnative

import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Optional androidTest-only localhost adapter. The host owns the authenticated SDK controller,
 * public JWK and actual kernel observer. Only a private correlation nonce and measured public
 * geometry cross this adb-reversed channel; it receives no product values or controller keys.
 * Host receipts prove transport; the caller must independently prove current app ownership and
 * actual TalkBack focus/activation. No request is retried, including a failed one.
 */
internal class TalkBackInputReaderTransport private constructor(private val port: Int, private val nonce: String) {
    private var sequence = 0
    private var verified = false

    fun touchExplore(witness: JSONObject, target: Rect, before: () -> Unit, after: () -> Unit) {
        check(verified) { "A genuine fresh kernel preflight is required before touch exploration" }
        assertTrue("Touch exploration requires the actual measured target", target.width() >= 4 && target.height() >= 4)
        val x = target.centerX()
        val y = target.centerY()
        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout()
        val tapMs = ViewConfiguration.getTapTimeout()
        val moveAt = doubleTapMs.toLong() + tapMs
        val upAt = moveAt + tapMs
        assertTrue("The public timing must fit the existing bounded single-contact fixture",
            doubleTapMs > 0 && tapMs > 0 && upAt <= 1_500)
        // Published TalkBack GestureConfiguration uses the public double-tap timeout minus
        // 50ms, and TouchInteractionMonitor schedules exploration rather than acting on a
        // one-finger move. Keep this same contact down beyond the full public timeout, with
        // two public tap intervals for dispatch. This does not read or assume installed prefs.
        println("LAVA_UI_TALKBACK_INPUTREADER stage=gesture kind=touch-explore publicDoubleTapMs=$doubleTapMs " +
            "publicTapMs=$tapMs moveAtMs=$moveAt upAtMs=$upAt installedProviderDelay=not-observed")
        exchange("gesture", witness, listOf(Frame(0, x, y, 1), Frame(moveAt.toInt(), x + 1, y, 1), Frame(upAt.toInt(), x + 1, y, 0)),
            1, before, after)
        println("LAVA_UI_TALKBACK_INPUTREADER stage=gesture kind=touch-explore kernel=down-move-up release=true qualifiedByTransport=false")
    }

    fun preflight(witness: JSONObject, assertOwned: () -> Unit) {
        check(!verified) { "InputReader preflight is single use" }
        val rect = witness.getJSONObject("app_rect")
        val left = rect.getInt("left")
        val y = (rect.getInt("top") + rect.getInt("bottom")) / 2
        val frames = listOf(Frame(0, left + 1, y, 1), Frame(80, left + 2, y, 1), Frame(160, left + 2, y, 0))
        exchange("preflight", witness, frames, 1, assertOwned, assertOwned)
        verified = true
        println("LAVA_UI_TALKBACK_INPUTREADER stage=preflight kernel=down-move-up release=true classification=direct-touch-screen authenticated=true")
    }

    fun gesture(swipeRight: Boolean, witness: JSONObject, before: () -> Unit, after: () -> Unit) {
        check(verified) { "A genuine fresh kernel preflight is required before TalkBack gestures" }
        val rect = witness.getJSONObject("app_rect")
        val width = rect.getInt("right") - rect.getInt("left")
        val height = rect.getInt("bottom") - rect.getInt("top")
        assertTrue("A measured gesture needs usable current app pixels", width >= 16 && height >= 16)
        val y = rect.getInt("top") + height / 2
        val frames = if (swipeRight) {
            val start = rect.getInt("left") + width / 4
            val distance = width / 2
            (0..6).map { step -> Frame(step * 35, start + distance * step / 6, y, 1) } +
                Frame(245, start + distance, y, 0)
        } else {
            val x = rect.getInt("left") + width / 2
            // One pixel of actual movement per stroke stays within tap slop while giving
            // the kernel observer a distinguishable move frame. Two contacts form one tap pair.
            listOf(Frame(0, x, y, 1), Frame(40, x + 1, y, 1), Frame(70, x + 1, y, 0),
                Frame(150, x, y, 1), Frame(190, x + 1, y, 1), Frame(220, x + 1, y, 0))
        }
        exchange("gesture", witness, frames, if (swipeRight) 1 else 2, before, after)
        println("LAVA_UI_TALKBACK_INPUTREADER stage=gesture kind=${if (swipeRight) "swipe-right" else "double-tap"} " +
            "kernel=down-move-up release=true qualifiedByTransport=false")
    }

    private data class Frame(val at: Int, val x: Int, val y: Int, val pressure: Int) {
        fun json() = JSONObject().put("at_ms", at).put("x", x).put("y", y).put("pressure", pressure)
    }

    private fun exchange(stage: String, witness: JSONObject, frames: List<Frame>, strokes: Int,
        before: () -> Unit, after: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        val id = UUID.randomUUID().toString()
        val next = ++sequence // A failure consumes the sequence too; never replay a gesture.
        val body = JSONObject(witness.toString()).put("nonce", nonce).put("request_id", id)
            .put("sequence", next).put("stage", stage).put("frames", JSONArray(frames.map(Frame::json)))
        before()
        var failure: Throwable? = null
        var completed = false
        try {
            val result = request("/v1/$stage", body, deadline, before)
            assertEquals("Require the exact host protocol", 1, result.getInt("protocol"))
            assertEquals("Refuse an unrelated host request receipt", id, result.getString("request_id"))
            assertEquals("Refuse a replayed host sequence", next, result.getInt("sequence"))
            assertEquals("Refuse a substituted host stage", stage, result.getString("stage"))
            assertEquals("Only the genuine SDK Protocol B transport is admitted", "SDK_EMULATOR_CONTROLLER_PROTOCOL_B", result.getString("transport"))
            val display = witness.getJSONObject("display")
            val classification = result.getJSONObject("classification")
            assertTrue("Require an actual kernel event device", classification.getString("event_path").matches(Regex("/dev/input/event[0-9]+")))
            assertTrue("Require actual InputReader and EventHub identities", classification.getInt("eventhub_id") > 0 && classification.getInt("inputreader_id") > 0)
            assertTrue("Require the observed device name", classification.getString("device_name").isNotBlank())
            val sources = classification.getJSONArray("sources")
            assertTrue("Require the actual TOUCHSCREEN classification", (0 until sources.length()).any { sources.getString(it) == "TOUCHSCREEN" })
            assertEquals("DIRECT", classification.getString("mode"))
            assertEquals("TOUCH_SCREEN", classification.getString("device_type"))
            assertEquals(0, classification.getInt("display_id"))
            assertEquals(0, classification.getInt("orientation"))
            assertEquals(display.getInt("width"), classification.getInt("width"))
            assertEquals(display.getInt("height"), classification.getInt("height"))
            assertTrue("The actual InputReader viewport must remain active", classification.getBoolean("active"))
            val kernel = result.getJSONObject("kernel")
            for (fact in listOf("down", "move", "up", "release")) assertTrue("Require observed kernel $fact", kernel.getBoolean(fact))
            assertEquals("Every intended frame must reach the kernel exactly once", frames.size, kernel.getInt("frames"))
            assertEquals("Every kernel frame must match the intended contact", frames.size, kernel.getInt("matched_frames"))
            assertEquals("Require exactly the intended number of complete contact strokes", strokes, kernel.getInt("strokes"))
            assertTrue("Require a real kernel tracking identity", kernel.getInt("tracking_id") >= 0)
            val rpc = result.getJSONObject("rpc")
            assertEquals("Every frame must also be accepted by the authenticated SDK endpoint", frames.size, rpc.getInt("send_touch_acks"))
            assertTrue("Pressure-zero SDK release must be acknowledged", rpc.getBoolean("release_acknowledged"))
            assertEquals("A host transport cannot certify TalkBack behavior", false, result.getBoolean("talkback_gesture_qualified"))
            after()
            remaining(deadline, 1)
            completed = true
        } catch (problem: Throwable) {
            failure = if (problem is JSONException) AssertionError("Malformed InputReader host receipt") else problem
            throw checkNotNull(failure)
        } finally {
            if (!completed) {
                try {
                    // Cancellation only targets this request's own contact; it cannot replay
                    // input or reset the controller. It shares the original bound, so the host's
                    // independent pressure-zero finally remains required even if this expires.
                    val cancel = request("/v1/cancel", JSONObject().put("nonce", nonce).put("request_id", id)
                        .put("pid", witness.getInt("pid")), deadline)
                    assertEquals(1, cancel.getInt("protocol"))
                    assertEquals(id, cancel.getString("request_id"))
                } catch (cleanup: Throwable) {
                    val safe = if (cleanup is JSONException) AssertionError("Malformed InputReader cancellation receipt") else cleanup
                    if (failure != null) failure.addSuppressed(safe) else throw safe
                }
            }
        }
    }

    private fun request(path: String, body: JSONObject, deadline: Long, beforeWrite: () -> Unit = {}): JSONObject {
        check(path in setOf("/v1/preflight", "/v1/gesture", "/v1/cancel"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        assertTrue("Bound the test-only request", bytes.size <= 8_192)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), remaining(deadline, 2_000))
            beforeWrite()
            remaining(deadline, 1) // Fresh app admission may itself wait; never dispatch late input.
            val header = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val input = socket.getInputStream()
            val status = line(input, socket, deadline)
            assertTrue("Require a complete host HTTP status", status.matches(Regex("HTTP/1\\.[01] [0-9]{3}( .*)?")))
            val statusCode = status.substring(9, 12).toInt()
            var length: Int? = null
            var json = false
            var total = status.length
            while (true) {
                val value = line(input, socket, deadline)
                total += value.length + 2
                assertTrue("Bound host response headers", total <= 4_096)
                if (value.isEmpty()) break
                val colon = value.indexOf(':')
                assertTrue("Require a well-formed host response header", colon > 0)
                val name = value.substring(0, colon).lowercase(java.util.Locale.ROOT)
                val content = value.substring(colon + 1).trim()
                when (name) {
                    "content-length" -> {
                        assertTrue("Refuse ambiguous response lengths", length == null)
                        length = content.toIntOrNull()
                        assertTrue("Require a valid response length", length != null)
                    }
                    "content-type" -> json = content.substringBefore(';').trim().equals("application/json", true)
                    "transfer-encoding" -> throw AssertionError("Chunked InputReader host responses are not admitted")
                }
            }
            assertTrue("Require a bounded JSON receipt", json && length != null && checkNotNull(length) in 1..16_384)
            val payload = ByteArray(checkNotNull(length))
            var offset = 0
            while (offset < payload.size) {
                socket.soTimeout = remaining(deadline, 10_000)
                val count = input.read(payload, offset, payload.size - offset)
                assertTrue("Refuse truncated host receipts", count > 0)
                offset += count
            }
            val result = JSONObject(String(payload, Charsets.UTF_8))
            if (statusCode != 200 || !result.getBoolean("ok")) {
                val code = result.optString("error").takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "HOST_REFUSED"
                throw AssertionError("InputReader host refused request: $code (HTTP $statusCode)")
            }
            remaining(deadline, 1)
            result
        }
    }

    private fun line(input: InputStream, socket: Socket, deadline: Long): String {
        val text = StringBuilder()
        while (true) {
            socket.soTimeout = remaining(deadline, 10_000)
            val byte = input.read()
            assertTrue("Refuse incomplete host headers", byte >= 0)
            if (byte == 10) {
                assertTrue("Require CRLF host headers", text.isNotEmpty() && text.last() == '\r')
                return text.dropLast(1).toString()
            }
            assertTrue("Bound ASCII host header lines", (byte == 13 || byte in 32..126) && text.length < 2_048)
            text.append(byte.toChar())
        }
    }

    private fun remaining(deadline: Long, maximum: Int): Int {
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("InputReader control must finish within the original twenty-second bound", remaining > 0)
        return remaining.coerceAtMost(maximum.toLong()).toInt()
    }

    companion object {
        fun fromArguments(): TalkBackInputReaderTransport {
            val arguments = InstrumentationRegistry.getArguments()
            val port = arguments.getString("talkbackBridgePort")?.toIntOrNull()?.takeIf { it in 1024..65535 }
                ?: throw AssertionError("TalkBack capability is unqualified: explicitly configure the verified task-owned localhost InputReader bridge")
            val nonce = arguments.getString("talkbackBridgeNonce")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: throw AssertionError("TalkBack capability is unqualified: require the private current host bridge nonce")
            return TalkBackInputReaderTransport(port, nonce)
        }
    }
}
