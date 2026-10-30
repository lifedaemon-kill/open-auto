package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectionIncomingSessionTest {
    @Test fun phoneInitiatedTcpConnectionUsesSameAuthenticatedSession() {
        val worker = Executors.newSingleThreadExecutor()
        ServerSocketChannel.open().use { listener ->
            listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val address = listener.localAddress as InetSocketAddress
            val phone = worker.submit {
                Socket(address.address, address.port).use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    assertEquals(1, AapMessageAssembler().accept(AapFrameCodec.read(input))!!.type)
                    AapFrameCodec.write(output, AapFrame(0, 3, byteArrayOf(0, 2, 0, 1, 0, 6, 0, 0)))
                    val engine = TlsTestIdentity.phone.server(TlsTestIdentity.headUnit.pin)
                    AapTlsHandshake(engine).execute({ AapFrameCodec.read(input) }, { AapFrameCodec.write(output, it) }, {})
                    assertArrayEquals(byteArrayOf(0, 4, 8, 0), AapFrameCodec.read(input).payload)
                    val tls = AapTlsChannel(engine, { AapFrameCodec.read(input) }, { AapFrameCodec.write(output, it) }, {})
                    tls.send(AapMessage(0, false, 5, proto { writeString(4, "Phone"); writeString(5, "Brand") }))
                    assertEquals(6, tls.receive().type)
                    tls.send(AapMessage(0, false, 11, proto { writeInt64(1, 100) }))
                    assertEquals(100L, numeric(tls.receive().body, 1))
                    tls.send(AapMessage(0, false, 15, proto { writeInt32(1, 1) }))
                    assertEquals(16, tls.receive().type)
                }
            }
            val accepted = listener.accept()
            ProjectionControlSession(acceptedChannel = accepted).use { session ->
                try {
                    val ended = session.execute(ProjectionEndpoint(address),
                        TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin), ProjectionHeadUnit("incoming-test"))
                    assertEquals(ControlSessionEnd.PEER_ENDED, ended)
                    phone.get(5, TimeUnit.SECONDS)
                    assertFalse(accepted.isOpen)
                } finally { worker.shutdownNow() }
            }
        }
    }
}
