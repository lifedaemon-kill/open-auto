package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine

class ProjectionControlSessionTest {
    private fun <T> withPhone(action: (AapTlsChannel, SSLEngine, (AapFrame) -> Unit) -> Unit,
        block: (ProjectionEndpoint, Future<*>) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            try {
                val phone = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        AapFrameCodec.read(input)
                        AapFrameCodec.write(output, AapFrame(0, 3, byteArrayOf(0, 2, 0, 1, 0, 6, 0, 0)))
                        val engine = TlsTestIdentity.phone.server(TlsTestIdentity.headUnit.pin)
                        AapTlsHandshake(engine).execute({ AapFrameCodec.read(input) },
                            { AapFrameCodec.write(output, it) }, {})
                        assertArrayEquals(byteArrayOf(0, 4, 8, 0), AapFrameCodec.read(input).payload)
                        val write: (AapFrame) -> Unit = { AapFrameCodec.write(output, it) }
                        action(AapTlsChannel(engine, { AapFrameCodec.read(input) }, write, {}), engine, write)
                    }
                }
                return block(ProjectionEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)), phone)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun keepsTlsAliveThroughDiscoverySeveralPingsAndPeerShutdown() {
        withPhone({ tls, _, _ ->
            tls.send(AapMessage(0, false, 5, proto { writeString(4, "Phone"); writeString(5, "Brand") }))
            assertEquals(6, tls.receive().type)
            for (timestamp in listOf(1L, Long.MAX_VALUE, 99L)) {
                tls.send(AapMessage(0, false, 11, proto { writeInt64(1, timestamp) }))
                val reply = tls.receive()
                assertEquals(12, reply.type)
                assertEquals(timestamp, numbers(reply.body)[1])
            }
            tls.send(AapMessage(0, false, 15, proto { writeEnum(1, 1) }))
            assertEquals(16, tls.receive().type)
            assertThrows(ProjectionTlsClosed::class.java) { tls.receive() }
        }) { endpoint, phone ->
            val events = mutableListOf<ControlSessionEvent>()
            val end = ProjectionControlSession().execute(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin),
                ProjectionHeadUnit("test"), onEvent = { events += it })
            assertEquals(ControlSessionEnd.PEER_ENDED, end)
            assertTrue(events.first() is ControlSessionEvent.Authenticated)
            assertTrue(events.contains(ControlSessionEvent.CapabilitiesSent))
            phone.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun decryptsEachFragmentBeforeAssemblingItsSplitMessageType() {
        withPhone({ tls, engine, write ->
            val plaintext = byteArrayOf(0, 11) + proto { writeInt64(1, 123) }
            fun fragment(bytes: ByteArray, flags: Int, total: Int? = null) {
                val network = ByteBuffer.allocate(65535)
                engine.wrap(ByteBuffer.wrap(bytes), network)
                write(AapFrame(0, flags or AapFrame.ENCRYPTED, network.array().copyOf(network.position()), total))
            }
            fragment(plaintext.copyOfRange(0, 1), AapFrame.FIRST, plaintext.size)
            fragment(plaintext.copyOfRange(1, plaintext.size), AapFrame.LAST)
            assertEquals(123L, numbers(tls.receive().body)[1])
            tls.send(AapMessage(0, false, 15, proto { writeEnum(1, 1) }))
            assertEquals(16, tls.receive().type)
        }) { endpoint, phone ->
            ProjectionControlSession().execute(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin),
                ProjectionHeadUnit("test"))
            phone.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun userStopSendsEncryptedByeByeAndCloseNotify() {
        val session = ProjectionControlSession()
        withPhone({ tls, _, _ ->
            tls.send(AapMessage(0, false, 5, proto { writeString(4, "Phone"); writeString(5, "Brand") }))
            assertEquals(6, tls.receive().type)
            val stop = tls.receive()
            assertEquals(15, stop.type)
            assertEquals(1L, numbers(stop.body)[1])
            assertThrows(ProjectionTlsClosed::class.java) { tls.receive() }
        }) { endpoint, phone ->
            val result = session.execute(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin),
                ProjectionHeadUnit("test"), onEvent = { if (it == ControlSessionEvent.CapabilitiesSent) session.requestStop() })
            assertEquals(ControlSessionEnd.USER_STOPPED, result)
            phone.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectsCleartextAfterTheAuthenticatedHandshake() {
        withPhone({ _, _, write -> write(AapFrame(0, 3, byteArrayOf(0, 11, 8, 1))) }) { endpoint, phone ->
            assertThrows(ProjectionProtocolException::class.java) {
                ProjectionControlSession().execute(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin),
                    ProjectionHeadUnit("test"))
            }
            phone.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun supportsMultipleTlsRecordsInOneControlEnvelope() {
        withPhone({ tls, _, _ ->
            // Unknown protobuf fields are skipped; the payload spans two TLS records.
            tls.send(AapMessage(0, false, 11, proto { writeInt64(1, 44); repeat(25) { writeByteArray(100, ByteArray(700)) } }))
            // The envelope is valid TLS; the bounded control parser must reject its >16 KiB body.
            assertThrows(ProjectionTlsClosed::class.java) { tls.receive() }
        }) { endpoint, phone ->
            assertThrows(ProjectionProtocolException::class.java) {
                ProjectionControlSession().execute(endpoint, TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin),
                    ProjectionHeadUnit("test"))
            }
            phone.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun stopBeforeConnectDoesNotOpenASocket() {
        val session = ProjectionControlSession()
        session.requestStop()
        assertEquals(ControlSessionEnd.USER_STOPPED, session.execute(ProjectionEndpoint.parse("192.168.1.2"),
            TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin), ProjectionHeadUnit("test")))
    }
}
