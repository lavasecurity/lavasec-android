package com.lavasec.app.data

import com.lavasec.app.foundation.filter.BlocklistFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeededSourceCacheTest {
    @Test
    fun `network transport rejects empty unsafe and mixed DNS sets before any socket is created`() = runBlocking {
        val public = InetAddress.getByName("1.1.1.1")
        val mappedPrivate = Inet6Address.getByAddress(null, ByteArray(16).apply {
            this[10] = 0xff.toByte(); this[11] = 0xff.toByte(); this[12] = 10; this[15] = 1
        }, -1)
        val unsafe = listOf("10.0.0.1", "172.16.0.1", "192.168.0.1", "127.0.0.1", "169.254.1.1",
            "0.0.0.0", "224.0.0.1", "240.0.0.1", "100.64.0.1", "198.18.0.1", "192.0.2.1",
            "::", "::1", "fc00::1", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1",
            "64:ff9b::a00:1").map(InetAddress::getByName) + mappedPrivate
        val answerSets = listOf(emptyList<InetAddress>()) + unsafe.flatMap { listOf(listOf(it), listOf(public, it), listOf(it, public)) }
        for (answers in answerSets) {
            val sockets = RefusingSocketFactory()
            var lookups = 0
            NetworkBoundSeededSourceTransport(sockets, { host ->
                assertEquals("source.example", host)
                lookups += 1
                answers
            }).use { transport ->
                assertCacheFailure(SeededSourceCacheFailure.NETWORK_IO) {
                    transport.execute("https://source.example/list.txt")
                }
            }
            assertEquals(1, lookups)
            assertEquals("DNS refusal must precede socket creation", 0, sockets.created.get())
        }
    }

    @Test
    fun `network transport rejects unsafe literal and credential URLs before the IP lookup bypass`() = runBlocking {
        for (url in listOf("https://127.0.0.1/list.txt", "https://10.0.0.1/list.txt", "https://[::1]/list.txt",
            "https://[::ffff:10.0.0.1]/list.txt", "https://user:secret@source.example/list.txt")) {
            val sockets = RefusingSocketFactory()
            var lookups = 0
            NetworkBoundSeededSourceTransport(sockets, {
                lookups += 1
                listOf(InetAddress.getByName("1.1.1.1"))
            }).use { transport ->
                assertCacheFailure(SeededSourceCacheFailure.NETWORK_IO) { transport.execute(url) }
            }
            assertEquals(0, lookups)
            assertEquals(0, sockets.created.get())
        }
    }

    @Test
    fun `public DNS remains ordered and a same-host redirect performs a newly admitted TLS route`() = withCache { root -> runBlocking {
        PublicSourceTlsPeer().use { peer ->
            val public = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
            var lookups = 0
            peer.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.txt")
                .setHeader("Connection", "close"))
            peer.server.enqueue(MockResponse().setBody("0.0.0.0 blocked.example\n"))
            val source = SOURCE.copy(id = "custom-public-dns", sourceUrl = peer.url,
                catalogRevision = "", catalogSha256 = "")
            peer.transport { host ->
                assertEquals("source.example", host)
                if (lookups++ == 0) public else public.reversed()
            }.use { transport ->
                val accepted = SeededSourceCache(root).acquireExact(source, transport)
                assertArrayEquals("0.0.0.0 blocked.example\n".encodeToByteArray(), accepted.file.readBytes())
            }
            assertEquals(2, lookups)
            assertEquals(public, peer.connectedAddresses.toList())
            assertEquals("/list.txt", peer.server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            val redirected = peer.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/final.txt", redirected.path)
            assertEquals("source.example:${peer.server.port}", redirected.getHeader("Host"))
        }
    } }

    @Test
    fun `redirect DNS rebinding cannot connect or replace the previously accepted custom source`() = withCache { root -> runBlocking {
        PublicSourceTlsPeer().use { peer ->
            val source = SOURCE.copy(id = "custom-rebinding", sourceUrl = peer.url,
                catalogRevision = "", catalogSha256 = "")
            val cache = SeededSourceCache(root)
            val previous = cache.acquireExact(source, RecordingTransport {
                SeededSourceHttpResponse.ok("0.0.0.0 previous.example\n".encodeToByteArray())
            })
            var lookups = 0
            peer.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/private.txt")
                .setHeader("Connection", "close"))
            peer.transport {
                if (lookups++ == 0) listOf(InetAddress.getByName("1.1.1.1"))
                else listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1"))
            }.use { transport ->
                assertCacheFailure(SeededSourceCacheFailure.NETWORK_IO) {
                    cache.acquireExact(source, transport, refreshSource = true)
                }
            }
            assertEquals(2, lookups)
            assertEquals(1, peer.createdSockets.get())
            assertEquals(listOf(InetAddress.getByName("1.1.1.1")), peer.connectedAddresses.toList())
            assertEquals(1, peer.server.requestCount)
            assertEquals(previous.acceptedSha256, SeededSourceCache(root).exactOrNull(source)!!.acceptedSha256)
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        }
    } }

    @Test
    fun `network transport retains hostname verification with an admitted public route`() = runBlocking {
        PublicSourceTlsPeer(certificateHost = "other.example").use { peer ->
            peer.transport { listOf(InetAddress.getByName("1.1.1.1")) }.use { transport ->
                assertCacheFailure(SeededSourceCacheFailure.NETWORK_IO) { transport.execute(peer.url) }
            }
            assertEquals(1, peer.createdSockets.get())
            assertEquals(0, peer.server.requestCount)
        }
    }

    @Test
    fun `cancellation closes an admitted real TLS request without publishing cache state`() = withCache { root -> runBlocking {
        PublicSourceTlsPeer().use { peer ->
            peer.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val source = SOURCE.copy(id = "cancel-public-dns", sourceUrl = peer.url,
                catalogRevision = "", catalogSha256 = "")
            peer.transport { listOf(InetAddress.getByName("1.1.1.1")) }.use { transport ->
                val pending = async(Dispatchers.IO) { SeededSourceCache(root).acquireExact(source, transport) }
                assertTrue("real request must reach the controlled TLS peer", peer.server.takeRequest(5, TimeUnit.SECONDS) != null)
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
            }
            assertNull(SeededSourceCache(root).exactOrNull(source))
            assertEquals(1, peer.createdSockets.get())
        }
    } }

    @Test
    fun `malformed custom refresh retains accepted bytes and selection across reopen`() = withCache { root -> runBlocking {
        val custom = SOURCE.copy(id = "custom-malformed", catalogRevision = "", catalogSha256 = "")
        val cache = SeededSourceCache(root)
        val previous = cache.acquireExact(custom, RecordingTransport {
            SeededSourceHttpResponse.ok("0.0.0.0 previous.example\n".encodeToByteArray())
        })
        for (bytes in listOf("bad syntax / path\n".encodeToByteArray(), byteArrayOf(0xff.toByte()), "x".repeat(4_097).encodeToByteArray())) {
            val error = runCatching {
                cache.acquireExact(custom, RecordingTransport { SeededSourceHttpResponse.ok(bytes) }, refreshSource = true)
            }.exceptionOrNull() as SeededSourceCacheException
            assertEquals(SeededSourceCacheFailure.PARSE_FAILED, error.reason)
            assertEquals(previous.acceptedSha256, cache.exactOrNull(custom)!!.acceptedSha256)
            assertEquals(previous.acceptedSha256, SeededSourceCache(root).exactOrNull(custom)!!.acceptedSha256)
            assertFalse(File(root, "${sha256(bytes)}.src").exists())
        }
    } }

    @Test
    fun `custom comments allow-only and mixed payloads are accepted rather than classified by zero rules`() = withCache { root -> runBlocking {
        val cache = SeededSourceCache(root)
        val custom = SOURCE.copy(id = "custom-valid", catalogRevision = "", catalogSha256 = "", format = BlocklistFormat.AUTO)
        for (text in listOf("# comments\n", "@@||allowed.example^\n", "apple.com\n", "good.example\n-invalid.example\n")) {
            val accepted = cache.acquireExact(custom, RecordingTransport { SeededSourceHttpResponse.ok(text.encodeToByteArray()) },
                refreshSource = true)
            assertEquals(sha256(text.encodeToByteArray()), accepted.acceptedSha256)
            assertTrue(accepted.asFilterPayload().isCustom)
        }
    } }

    @Test
    fun `decoded chunked body is streamed into one exact accepted payload`() = withCache { root -> runBlocking {
        val chunks = listOf(
            "0.0.0.0 chunked-".encodeToByteArray(),
            "block.example\n0.0.0.0 second".encodeToByteArray(),
            "-block.example\n".encodeToByteArray(),
        )
        val expected = chunks.fold(byteArrayOf()) { bytes, chunk -> bytes + chunk }
        val cache = SeededSourceCache(root)

        val accepted = cache.acquireExact(
            SOURCE.copy(id = "chunked-source"),
            RecordingTransport { SeededSourceHttpResponse(200, null, ChunkedInputStream(chunks)) },
        )

        assertEquals(expected.size.toLong(), accepted.acceptedByteSize)
        assertEquals(sha256(expected), accepted.acceptedSha256)
        assertArrayEquals(expected, accepted.file.readBytes())
    } }

    @Test
    fun `acquisition timeout cancels the live response and is returned as a typed source failure`() =
        withCache { root -> runBlocking {
            val responseCreated = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val cancelCalls = AtomicInteger()
            val cache = SeededSourceCache(root, acquisitionTimeoutMillis = 50)
            val startedAtNanos = System.nanoTime()
            val error = runCatching {
                cache.acquireExact(
                    SOURCE.copy(id = "timeout-source"),
                    RecordingTransport {
                        responseCreated.countDown()
                        SeededSourceHttpResponse(
                            statusCode = 200,
                            redirectLocation = null,
                            body = object : InputStream() {
                                override fun read(): Int {
                                    releaseRead.await(5, TimeUnit.SECONDS)
                                    return -1
                                }
                            },
                            cancelCall = {
                                cancelCalls.incrementAndGet()
                                releaseRead.countDown()
                            },
                        )
                    },
                )
            }.exceptionOrNull()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertTrue(responseCreated.await(5, TimeUnit.SECONDS))
            assertTrue("timeout took ${elapsedMillis}ms", elapsedMillis < 2_000)
            assertTrue(error is SeededSourceCacheException)
            assertEquals(SeededSourceCacheFailure.TIMEOUT, (error as SeededSourceCacheException).reason)
            assertEquals(1, cancelCalls.get())
            assertNull(cache.exactOrNull(SOURCE.copy(id = "timeout-source")))
        } }

    @Test
    fun `download publishes a digest-addressed immutable payload and exact manifest is reused`() =
        withCache { root -> runBlocking {
            val body = "0.0.0.0 blocked.example\n".encodeToByteArray()
            val transport = RecordingTransport { SeededSourceHttpResponse.ok(body) }
            val cache = SeededSourceCache(root)

            val first = cache.acquireExact(SOURCE, transport)
            val second = cache.acquireExact(SOURCE, FailingTransport)

            assertEquals(sha256(body), first.acceptedSha256)
            assertEquals(body.size.toLong(), first.acceptedByteSize)
            assertEquals(first, second)
            assertEquals(listOf(SOURCE.sourceUrl), transport.urls)
            assertArrayEquals(body, first.file.readBytes())
            assertTrue(first.file.isFile)
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `source refresh bypasses an exact manifest while normal acquisition still reuses it`() =
        withCache { root -> runBlocking {
            val oldBody = "0.0.0.0 old.example\n".encodeToByteArray()
            val refreshedBody = "0.0.0.0 refreshed.example\n".encodeToByteArray()
            val initialTransport = RecordingTransport { SeededSourceHttpResponse.ok(oldBody) }
            val refreshTransport = RecordingTransport { SeededSourceHttpResponse.ok(refreshedBody) }
            val cache = SeededSourceCache(root)

            val initial = cache.acquireExact(SOURCE, initialTransport)
            val normal = cache.acquireExact(SOURCE, FailingTransport, refreshSource = false)
            val refreshed = cache.acquireExact(SOURCE, refreshTransport, refreshSource = true)

            assertEquals(initial, normal)
            assertEquals(sha256(refreshedBody), refreshed.acceptedSha256)
            assertEquals(listOf(SOURCE.sourceUrl), refreshTransport.urls)
            assertEquals(refreshed, cache.exactOrNull(SOURCE))
        } }

    @Test
    fun `only https is accepted across at most five redirects`() = withCache { root -> runBlocking {
        val cache = SeededSourceCache(root)
        val locations = ArrayDeque(
            listOf(
                "/one", "/two", "/three", "/four", "/five",
            ),
        )
        val transport = RecordingTransport {
            if (locations.isEmpty()) SeededSourceHttpResponse.ok("blocked.example\n".encodeToByteArray())
            else SeededSourceHttpResponse.redirect(locations.removeFirst())
        }

        val accepted = cache.acquireExact(SOURCE, transport)
        assertTrue(accepted.file.isFile)
        assertEquals(6, transport.urls.size)

        val insecure = SOURCE.copy(sourceUrl = "http://example.test/list.txt")
        assertCacheFailure(SeededSourceCacheFailure.HTTPS_REQUIRED) {
            cache.acquireExact(insecure, transport)
        }

        val tooMany = RecordingTransport { SeededSourceHttpResponse.redirect("/again") }
        assertCacheFailure(SeededSourceCacheFailure.REDIRECT_LIMIT) {
            cache.acquireExact(SOURCE.copy(id = "another-source"), tooMany)
        }
        assertEquals(6, tooMany.urls.size)

        val downgrade = RecordingTransport { SeededSourceHttpResponse.redirect("http://example.test/list") }
        assertCacheFailure(SeededSourceCacheFailure.HTTPS_REQUIRED) {
            cache.acquireExact(SOURCE.copy(id = "downgrade-source"), downgrade)
        }

        val crossHost = RecordingTransport {
            SeededSourceHttpResponse.redirect("https://redirected.example/list")
        }
        assertCacheFailure(SeededSourceCacheFailure.REDIRECT_HOST_MISMATCH) {
            cache.acquireExact(SOURCE.copy(id = "cross-host-source"), crossHost)
        }
    } }

    @Test
    fun `cache reuse preserves accepted source time and successful refresh advances it`() =
        withCache { root -> runBlocking {
            val clock = AtomicLong(1_000L)
            val cache = SeededSourceCache(root, nowEpochMillis = clock::get)
            val first = cache.acquireExact(
                SOURCE,
                RecordingTransport { SeededSourceHttpResponse.ok("first.example\n".encodeToByteArray()) },
            )

            clock.set(2_000L)
            val reused = cache.acquireExact(SOURCE, FailingTransport)
            val refreshed = cache.acquireExact(
                SOURCE,
                RecordingTransport { SeededSourceHttpResponse.ok("second.example\n".encodeToByteArray()) },
                refreshSource = true,
            )

            assertEquals(1_000L, first.acceptedAtEpochMillis)
            assertEquals(1_000L, reused.acceptedAtEpochMillis)
            assertEquals(2_000L, refreshed.acceptedAtEpochMillis)
        } }

    @Test
    fun `failed refresh preserves prior accepted source time`() = withCache { root -> runBlocking {
        val clock = AtomicLong(1_000L)
        val cache = SeededSourceCache(root, nowEpochMillis = clock::get)
        val accepted = cache.acquireExact(
            SOURCE,
            RecordingTransport { SeededSourceHttpResponse.ok("base.example\n".encodeToByteArray()) },
        )
        clock.set(2_000L)

        assertCacheFailure(SeededSourceCacheFailure.HTTP_STATUS) {
            cache.acquireExact(
                SOURCE,
                RecordingTransport {
                    SeededSourceHttpResponse(503, null, ByteArrayInputStream(byteArrayOf()))
                },
                refreshSource = true,
            )
        }

        assertEquals(1_000L, accepted.acceptedAtEpochMillis)
        assertEquals(1_000L, cache.exactOrNull(SOURCE)?.acceptedAtEpochMillis)
    } }

    @Test
    fun `schema one manifest migrates immutable file time into accepted provenance`() =
        withCache { root ->
            val bytes = "legacy.example\n".encodeToByteArray()
            val digest = sha256(bytes)
            val immutable = File(root, "$digest.src").apply {
                writeBytes(bytes)
                assertTrue(setLastModified(1_234L))
            }
            val legacyManifest = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write("LAVASRC1".encodeToByteArray())
                    output.writeInt(1)
                    listOf(
                        SOURCE.id,
                        SOURCE.catalogRevision,
                        SOURCE.catalogSha256,
                        SOURCE.sourceUrl,
                    ).forEach { value ->
                        val encoded = value.encodeToByteArray()
                        output.writeInt(encoded.size)
                        output.write(encoded)
                    }
                    output.writeInt(SOURCE.format.ordinal)
                    val encodedDigest = digest.encodeToByteArray()
                    output.writeInt(encodedDigest.size)
                    output.write(encodedDigest)
                    output.writeLong(bytes.size.toLong())
                }
                buffer.toByteArray()
            }
            File(root, "${SOURCE.id}.manifest").writeBytes(legacyManifest)

            val accepted = SeededSourceCache(root).exactOrNull(SOURCE)

            assertEquals(1_234L, accepted?.acceptedAtEpochMillis)
            assertEquals(immutable, accepted?.file)
            DataInputStream(File(root, "${SOURCE.id}.manifest").inputStream()).use { input ->
                input.skipBytes("LAVASRC1".length)
                assertEquals(2, input.readInt())
            }
        }

    @Test
    fun `repeated refresh retains only current and immediate previous payload`() =
        withCache { root -> runBlocking {
            val cache = SeededSourceCache(root, maximumCacheBytes = 1_024L)
            val bodies = listOf("first.example\n", "second.example\n", "third.example\n")
                .map(String::encodeToByteArray)

            bodies.forEach { body ->
                cache.acquireExact(
                    SOURCE,
                    RecordingTransport { SeededSourceHttpResponse.ok(body) },
                    refreshSource = true,
                )
            }

            val retained = root.listFiles().orEmpty()
                .filter { it.name.endsWith(".src") }
                .map { it.nameWithoutExtension }
                .toSet()
            assertEquals(setOf(sha256(bodies[1]), sha256(bodies[2])), retained)
            assertEquals(sha256(bodies[2]), cache.exactOrNull(SOURCE)?.acceptedSha256)
        } }

    @Test
    fun `recreation removes process death payload and manifest temporaries`() = withCache { root ->
        val orphanPayload = File(root, ".payload-11111111-1111-1111-1111-111111111111.tmp")
        val orphanManifest = File(root, ".manifest-22222222-2222-2222-2222-222222222222.tmp")
        orphanPayload.writeBytes(ByteArray(2_048) { 1 })
        orphanManifest.writeBytes(ByteArray(512) { 2 })
        val unrelated = File(root, ".payload-not-a-uuid.tmp").apply { writeText("keep") }

        SeededSourceCache(root, maximumCacheBytes = 1_024L)

        assertFalse(orphanPayload.exists())
        assertFalse(orphanManifest.exists())
        assertTrue(unrelated.isFile)
    }

    @Test
    fun `recovery protected source survives refresh retention`() = withCache { root -> runBlocking {
        val protected = linkedSetOf<String>()
        val cache = SeededSourceCache(root, protectedDigests = { protected.toSet() })
        val bodies = listOf("base.example\n", "previous.example\n", "current.example\n")
            .map { it.encodeToByteArray() }

        val base = cache.acquireExact(
            SOURCE,
            RecordingTransport { SeededSourceHttpResponse.ok(bodies[0]) },
            refreshSource = true,
        )
        protected += base.acceptedSha256
        bodies.drop(1).forEach { body ->
            cache.acquireExact(
                SOURCE,
                RecordingTransport { SeededSourceHttpResponse.ok(body) },
                refreshSource = true,
            )
        }

        val retained = root.listFiles().orEmpty()
            .filter { it.name.endsWith(".src") }
            .map { it.nameWithoutExtension }
            .toSet()
        assertEquals(bodies.mapTo(linkedSetOf(), ::sha256), retained)
    } }

    @Test
    fun `refresh fails instead of evicting protected rollback payload`() = withCache { root -> runBlocking {
        val protected = linkedSetOf<String>()
        val cache = SeededSourceCache(
            root,
            maximumCacheBytes = 25L,
            protectedDigests = { protected.toSet() },
        )
        val baseBytes = "base.example\n".encodeToByteArray()
        val replacementBytes = "replacement.example\n".encodeToByteArray()
        val base = cache.acquireExact(
            SOURCE,
            RecordingTransport { SeededSourceHttpResponse.ok(baseBytes) },
        )
        protected += base.acceptedSha256

        assertCacheFailure(SeededSourceCacheFailure.STORAGE_IO) {
            cache.acquireExact(
                SOURCE,
                RecordingTransport { SeededSourceHttpResponse.ok(replacementBytes) },
                refreshSource = true,
            )
        }

        assertEquals(base.acceptedSha256, cache.exactOrNull(SOURCE)?.acceptedSha256)
        assertTrue(base.file.isFile)
        assertFalse(File(root, "${sha256(replacementBytes)}.src").exists())
    } }

    @Test
    fun `non-success and oversized bodies fail without selecting stale or partial data`() =
        withCache { root -> runBlocking {
            val cache = SeededSourceCache(root)
            assertCacheFailure(SeededSourceCacheFailure.HTTP_STATUS) {
                cache.acquireExact(SOURCE, RecordingTransport { SeededSourceHttpResponse(503, null, ByteArrayInputStream(byteArrayOf())) })
            }
            assertNull(cache.exactOrNull(SOURCE))

            val oversized = object : InputStream() {
                private var remaining = SeededSourceCache.MAX_DECODED_BODY_BYTES + 1L
                override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(length.toLong(), remaining).toInt()
                    buffer.fill('x'.code.toByte(), offset, offset + count)
                    remaining -= count
                    return count
                }
            }
            assertCacheFailure(SeededSourceCacheFailure.BODY_TOO_LARGE) {
                cache.acquireExact(
                    SOURCE,
                    RecordingTransport { SeededSourceHttpResponse(200, null, oversized) },
                )
            }
            assertNull(cache.exactOrNull(SOURCE))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `exactly forty five MiB is accepted`() = withCache { root -> runBlocking {
        val source = SOURCE.copy(id = "exact-boundary")
        val cache = SeededSourceCache(root)

        val accepted = cache.acquireExact(
            source,
            RecordingTransport {
                SeededSourceHttpResponse(200, null, RepeatingInputStream(SeededSourceCache.MAX_DECODED_BODY_BYTES))
            },
        )

        assertEquals(SeededSourceCache.MAX_DECODED_BODY_BYTES, accepted.acceptedByteSize)
        assertEquals(SeededSourceCache.MAX_DECODED_BODY_BYTES, accepted.file.length())
    } }

    @Test
    fun `sync and atomic rename failures clean partials and never publish a manifest`() =
        withCache { root -> runBlocking {
            val bytes = "blocked.example\n".encodeToByteArray()
            val syncFailure = SeededSourceCache(
                root,
                object : SeededSourceFileOperations by SeededSourceFileOperations.SYSTEM {
                    override fun sync(output: FileOutputStream) = throw java.io.IOException("fsync")
                },
            )
            assertCacheFailure(SeededSourceCacheFailure.STORAGE_IO) {
                syncFailure.acquireExact(SOURCE, RecordingTransport { SeededSourceHttpResponse.ok(bytes) })
            }
            assertNull(syncFailure.exactOrNull(SOURCE))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })

            val renameFailure = SeededSourceCache(
                root,
                object : SeededSourceFileOperations by SeededSourceFileOperations.SYSTEM {
                    override fun atomicMove(source: File, target: File, replace: Boolean) =
                        throw java.io.IOException("rename")
                },
            )
            assertCacheFailure(SeededSourceCacheFailure.STORAGE_IO) {
                renameFailure.acquireExact(SOURCE, RecordingTransport { SeededSourceHttpResponse.ok(bytes) })
            }
            assertNull(renameFailure.exactOrNull(SOURCE))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `manifest identity length digest url and format mismatches are never reused`() =
        withCache { root -> runBlocking {
            val bytes = "blocked.example\n".encodeToByteArray()
            val cache = SeededSourceCache(root)
            val accepted = cache.acquireExact(SOURCE, RecordingTransport { SeededSourceHttpResponse.ok(bytes) })

            assertNull(cache.exactOrNull(SOURCE.copy(catalogRevision = "new-revision")))
            assertNull(cache.exactOrNull(SOURCE.copy(catalogSha256 = "b".repeat(64))))
            assertNull(cache.exactOrNull(SOURCE.copy(sourceUrl = "https://example.test/other")))
            assertNull(cache.exactOrNull(SOURCE.copy(format = BlocklistFormat.PLAIN_DOMAINS)))

            accepted.file.appendText("corrupt")
            assertNull(cache.exactOrNull(SOURCE))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `cancellation deletes partials and retains the last fully accepted exact cache`() =
        withCache { root -> runBlocking {
            val old = "old.example\n".encodeToByteArray()
            val cache = SeededSourceCache(root)
            val accepted = cache.acquireExact(SOURCE, RecordingTransport { SeededSourceHttpResponse.ok(old) })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val replacement = SOURCE.copy(catalogRevision = "replacement")
            val operation = async {
                cache.acquireExact(
                    replacement,
                    RecordingTransport {
                        SeededSourceHttpResponse(
                            200,
                            null,
                            object : InputStream() {
                                override fun read(): Int {
                                    entered.complete(Unit)
                                    runBlocking { release.await() }
                                    return -1
                                }
                            },
                        )
                    },
                )
            }
            entered.await()
            operation.cancel(CancellationException("superseded"))
            release.complete(Unit)
            runCatching { operation.await() }

            assertEquals(accepted, cache.exactOrNull(SOURCE))
            assertNull(cache.exactOrNull(replacement))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `cancelled acquisition deletes only its owned temporary while a newer acquisition publishes`() =
        withCache { root -> runBlocking {
            val firstSyncEntered = CountDownLatch(1)
            val secondSyncEntered = CountDownLatch(1)
            val releaseFirstSync = CountDownLatch(1)
            val releaseSecondSync = CountDownLatch(1)
            val syncCalls = AtomicInteger()
            val operations = object : SeededSourceFileOperations by SeededSourceFileOperations.SYSTEM {
                override fun sync(output: FileOutputStream) {
                    when (syncCalls.incrementAndGet()) {
                        1 -> {
                            firstSyncEntered.countDown()
                            check(releaseFirstSync.await(5, TimeUnit.SECONDS))
                        }
                        2 -> {
                            secondSyncEntered.countDown()
                            check(releaseSecondSync.await(5, TimeUnit.SECONDS))
                        }
                    }
                    SeededSourceFileOperations.SYSTEM.sync(output)
                }
            }
            val cache = SeededSourceCache(root, operations)
            val oldSource = SOURCE.copy(id = "old-operation")
            val newSource = SOURCE.copy(id = "new-operation")
            val old = async(Dispatchers.IO) {
                cache.acquireExact(
                    oldSource,
                    RecordingTransport { SeededSourceHttpResponse.ok("old.example\n".encodeToByteArray()) },
                )
            }
            assertTrue(firstSyncEntered.await(5, TimeUnit.SECONDS))
            val newer = async(Dispatchers.IO) {
                cache.acquireExact(
                    newSource,
                    RecordingTransport { SeededSourceHttpResponse.ok("new.example\n".encodeToByteArray()) },
                )
            }
            assertTrue(secondSyncEntered.await(5, TimeUnit.SECONDS))

            old.cancel(CancellationException("superseded"))
            releaseFirstSync.countDown()
            runCatching { old.await() }
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith(".payload-") && it.name.endsWith(".tmp") })

            releaseSecondSync.countDown()
            val accepted = newer.await()
            assertEquals(accepted, cache.exactOrNull(newSource))
            assertNull(cache.exactOrNull(oldSource))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        } }

    @Test
    fun `retention never deletes another live acquisition temporary`() = withCache { root -> runBlocking {
        val firstPayloadSyncEntered = CountDownLatch(1)
        val secondPayloadSyncEntered = CountDownLatch(1)
        val releaseFirstPayload = CountDownLatch(1)
        val releaseSecondPayload = CountDownLatch(1)
        val syncCalls = AtomicInteger()
        val operations = object : SeededSourceFileOperations by SeededSourceFileOperations.SYSTEM {
            override fun sync(output: FileOutputStream) {
                when (syncCalls.incrementAndGet()) {
                    1 -> {
                        firstPayloadSyncEntered.countDown()
                        check(releaseFirstPayload.await(5, TimeUnit.SECONDS))
                    }
                    2 -> {
                        secondPayloadSyncEntered.countDown()
                        check(releaseSecondPayload.await(5, TimeUnit.SECONDS))
                    }
                }
                SeededSourceFileOperations.SYSTEM.sync(output)
            }
        }
        val cache = SeededSourceCache(root, operations)
        val firstSource = SOURCE.copy(id = "first-live-acquisition")
        val secondSource = SOURCE.copy(id = "second-live-acquisition")
        val first = async(Dispatchers.IO) {
            cache.acquireExact(
                firstSource,
                RecordingTransport { SeededSourceHttpResponse.ok("first.example\n".encodeToByteArray()) },
            )
        }
        assertTrue(firstPayloadSyncEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) {
            cache.acquireExact(
                secondSource,
                RecordingTransport { SeededSourceHttpResponse.ok("second.example\n".encodeToByteArray()) },
            )
        }
        assertTrue(secondPayloadSyncEntered.await(5, TimeUnit.SECONDS))
        SeededSourceCache(root)

        releaseFirstPayload.countDown()
        val firstAccepted = first.await()
        releaseSecondPayload.countDown()
        val secondAccepted = second.await()

        assertEquals(firstAccepted, cache.exactOrNull(firstSource))
        assertEquals(secondAccepted, cache.exactOrNull(secondSource))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    } }

    private suspend fun assertCacheFailure(
        expected: SeededSourceCacheFailure,
        block: suspend () -> Unit,
    ) {
        val actual = runCatching { block() }.exceptionOrNull()
        assertTrue("expected $expected, got $actual", actual is SeededSourceCacheException)
        assertEquals(expected, (actual as SeededSourceCacheException).reason)
    }

    private class RefusingSocketFactory : SocketFactory() {
        val created = AtomicInteger()
        override fun createSocket(): Socket {
            created.incrementAndGet()
            throw IOException("a refused DNS answer must never create a socket")
        }
        override fun createSocket(host: String, port: Int): Socket = createSocket()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
    }

    /**
     * Real TLS/HTTP peer with a synthetic public DNS answer. Only this fixture socket maps the
     * admitted route onto loopback; production keeps the captured Android Network socket factory.
     * The certificate identifies the logical source host, never loopback or the synthetic IPs.
     */
    private class PublicSourceTlsPeer(certificateHost: String = "source.example") : Closeable {
        private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(certificateHost).build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(serverTls.sslSocketFactory(), false)
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        val createdSockets = AtomicInteger()
        val connectedAddresses = Collections.synchronizedList(mutableListOf<InetAddress>())
        val url = "https://source.example:${server.port}/list.txt"
        private val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket {
                createdSockets.incrementAndGet()
                return object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        val route = endpoint as InetSocketAddress
                        connectedAddresses += route.address
                        if (route.port != server.port || route.address !in listOf(
                                InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))) {
                            throw IOException("fixture refuses an unexpected logical route")
                        }
                        super.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.port), timeout)
                    }
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("OkHttp must use the resolved route")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("unexpected factory overload")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unexpected factory overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("unexpected factory overload")
        }

        fun transport(resolve: (String) -> List<InetAddress>) = NetworkBoundSeededSourceTransport(
            socketFactory, resolve, clientTls.sslSocketFactory(), clientTls.trustManager,
        )

        override fun close() { server.shutdown() }
    }

    private inline fun withCache(block: (File) -> Unit) {
        val root = createTempDirectory("seeded-cache-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private class RecordingTransport(
        private val response: suspend (String) -> SeededSourceHttpResponse,
    ) : SeededSourceTransport {
        val urls = mutableListOf<String>()
        override suspend fun execute(url: String): SeededSourceHttpResponse {
            urls += url
            return response(url)
        }
    }

    private object FailingTransport : SeededSourceTransport {
        override suspend fun execute(url: String): SeededSourceHttpResponse =
            error("exact cache should have been reused")
    }

    private class RepeatingInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill('x'.code.toByte(), offset, offset + count)
            remaining -= count
            return count
        }
    }

    private class ChunkedInputStream(chunks: List<ByteArray>) : InputStream() {
        private val chunks = ArrayDeque(chunks)
        private var current = byteArrayOf()
        private var offset = 0

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (this.offset >= current.size) {
                if (chunks.isEmpty()) return -1
                current = chunks.removeFirst()
                this.offset = 0
            }
            val count = minOf(length, current.size - this.offset)
            current.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
    }

    private companion object {
        val SOURCE = SeededSourceDescriptor(
            id = "blocklistproject-basic",
            sourceUrl = "https://blocklistproject.github.io/Lists/basic.txt",
            format = BlocklistFormat.HOSTS,
            catalogRevision = "catalog-revision",
            catalogSha256 = "a".repeat(64),
        )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
