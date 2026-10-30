package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ProjectionTlsTest {
    private data class PhoneResult(val tls: ProjectionTlsResult?, val auth: AapFrame?, val failure: Exception?)
    private fun endpoint(server: ServerSocket) = ProjectionEndpoint(
        InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))

    private fun <T> withPhone(
        identity: TlsTestIdentity = TlsTestIdentity.phone, fragmented: Boolean = false,
        block: (ProjectionEndpoint, Future<PhoneResult>) -> T
    ): T {
        val executor = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val phone = executor.submit<PhoneResult> {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    try {
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val request = AapFrameCodec.read(input)
                        assertArrayEquals(byteArrayOf(0, 1, 0, 1, 0, 2), request.payload)
                        AapFrameCodec.write(output, AapFrame(0, 3, byteArrayOf(0, 2, 0, 1, 0, 6, 0, 0)))
                        val result = AapTlsHandshake(identity.server(TlsTestIdentity.headUnit.pin)).execute(
                            { AapFrameCodec.read(input) },
                            { frame ->
                                if (!fragmented) AapFrameCodec.write(output, frame)
                                else {
                                    // Split TLS records across AAP messages AND split the AAP type itself.
                                    frame.payload.copyOfRange(2, frame.payload.size).asList().chunked(17).forEach { chunk ->
                                        val payload = byteArrayOf(0, 3) + chunk.toByteArray()
                                        AapFrameCodec.write(output, AapFrame(0, 1, byteArrayOf(0), payload.size))
                                        AapFrameCodec.write(output, AapFrame(0, 2, payload.copyOfRange(1, payload.size)))
                                    }
                                }
                            }, {})
                        PhoneResult(result, AapFrameCodec.read(input), null)
                    } catch (failure: Exception) { PhoneResult(null, null, failure) }
                }
            }
            return block(endpoint(server), phone)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun realMutualTls12AndAuthCompleteUseTheVersionSocket() = withPhone { endpoint, phone ->
        val result = ProjectionVersionProbe().executeTls(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin))
        assertEquals("TLSv1.2", result.tls.protocol)
        assertFalse(result.tls.cipherSuite.contains("NULL"))
        assertEquals(6, result.version.minor)
        val remote = phone.get(5, TimeUnit.SECONDS)
        assertNull(remote.failure)
        assertEquals("TLSv1.2", remote.tls!!.protocol)
        assertArrayEquals(byteArrayOf(0, 4, 8, 0), remote.auth!!.payload)
        assertFalse(remote.auth.encrypted)
    }

    @Test fun handlesFragmentedTlsRecordsAndSplitAapTypes() = withPhone(fragmented = true) { endpoint, phone ->
        val result = ProjectionVersionProbe().executeTls(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin))
        assertEquals("TLSv1.2", result.tls.protocol)
        assertNull(phone.get(5, TimeUnit.SECONDS).failure)
    }

    @Test fun unknownPeerRequiresExplicitReviewAndSendsNoAuthComplete() = withPhone { endpoint, phone ->
        val rejection = assertThrows(ProjectionPeerReviewRequired::class.java) {
            ProjectionVersionProbe().executeTls(endpoint, TlsTestIdentity.headUnit.client(null))
        }
        assertEquals(TlsTestIdentity.phone.pin, rejection.fingerprint)
        assertFalse(rejection.changed)
        assertNull(phone.get(5, TimeUnit.SECONDS).auth)
    }

    @Test fun aChangedPinIsNeverAcceptedAutomatically() = withPhone { endpoint, phone ->
        val rejection = assertThrows(ProjectionPeerReviewRequired::class.java) {
            ProjectionVersionProbe().executeTls(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.headUnit.pin))
        }
        assertTrue(rejection.changed)
        assertEquals(TlsTestIdentity.phone.pin, rejection.fingerprint)
        assertNull(phone.get(5, TimeUnit.SECONDS).auth)
    }

    @Test fun evenAPinnedExpiredCertificateFails() {
        val expired = TlsTestIdentity.create("Expired", expired = true)
        withPhone(identity = expired) { endpoint, phone ->
            assertThrows(SSLException::class.java) {
                ProjectionVersionProbe().executeTls(endpoint, TlsTestIdentity.headUnit.client(expired.pin))
            }
            assertNull(phone.get(5, TimeUnit.SECONDS).auth)
        }
    }

    @Test fun tlsCancellationClosesTheBlockedConnection() {
        val executor = Executors.newFixedThreadPool(2)
        val hello = CountDownLatch(1)
        val probe = ProjectionVersionProbe()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            try {
                val phone = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        AapFrameCodec.read(input)
                        AapFrameCodec.write(socket.getOutputStream(), AapFrame(0, 3, byteArrayOf(0, 2, 0, 1, 0, 2, 0, 0)))
                        assertEquals(3, AapMessageAssembler().accept(AapFrameCodec.read(input))!!.type)
                        hello.countDown()
                        assertEquals(-1, input.read())
                    }
                }
                val client = TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin)
                val result = executor.submit<Exception?> {
                    try { probe.executeTls(endpoint(server), client); null } catch (e: Exception) { e }
                }
                assertTrue(hello.await(3, TimeUnit.SECONDS))
                probe.close()
                assertNotNull(result.get(2, TimeUnit.SECONDS))
                phone.get(4, TimeUnit.SECONDS)
            } finally { probe.close(); executor.shutdownNow() }
        }
    }

    @Test fun nonReadingPeerCannotBlockWritesPastTheDeadline() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.receiveBufferSize = 1024
            try {
                val phone = executor.submit { server.accept().use { release.await(3, TimeUnit.SECONDS) } }
                AapTcpTransport().use { transport ->
                    transport.beginPhase(400)
                    transport.connect(endpoint(server))
                    val frame = AapFrame(0, 3, ByteArray(65535))
                    assertThrows(SocketTimeoutException::class.java) { repeat(1024) { transport.writeFrame(frame) } }
                }
                release.countDown()
                phone.get(4, TimeUnit.SECONDS)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
}
