package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectionMediaSessionTest {
    @Test fun videoCreditsAndTouchCanBeSentWhilePhoneWaitsSilently() {
        val executor = Executors.newFixedThreadPool(2)
        val sink = RecordingMediaSink()
        val session = ProjectionControlSession(sink)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val phone = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    AapFrameCodec.read(input)
                    AapFrameCodec.write(output, AapFrame(0, 3, byteArrayOf(0, 2, 0, 1, 0, 6, 0, 0)))
                    val engine = TlsTestIdentity.phone.server(TlsTestIdentity.headUnit.pin)
                    AapTlsHandshake(engine).execute({ AapFrameCodec.read(input) }, { AapFrameCodec.write(output, it) }, {})
                    assertArrayEquals(byteArrayOf(0, 4, 8, 0), AapFrameCodec.read(input).payload)
                    val tls = AapTlsChannel(engine, { AapFrameCodec.read(input) }, { AapFrameCodec.write(output, it) }, {})
                    tls.send(AapMessage(0, false, 5, proto { writeString(4, "Phone"); writeString(5, "Brand") }))
                    assertEquals(6, tls.receive().type)
                    for (channel in listOf(1, 2)) {
                        tls.send(AapMessage(channel, true, 7, proto { writeSInt32(1, 0); writeInt32(2, channel) }))
                        assertEquals(0L, numbers(tls.receive().body)[1])
                    }
                    tls.send(AapMessage(2, false, 0x8002, byteArrayOf()))
                    assertEquals(0x8003, tls.receive().type)
                    assertTrue(session.touch(ProjectionTouch(100, 0, 0, listOf(ProjectionPointer(0, 10, 20)))))
                    assertEquals(0x8001, tls.receive().type)
                    tls.send(AapMessage(1, false, 0x8000, proto { writeUInt32(1, 3) }))
                    assertEquals(0x8003, tls.receive().type)
                    assertEquals(0x8008, tls.receive().type)
                    tls.send(AapMessage(1, false, 0x8001, proto { writeInt32(1, 4); writeUInt32(2, 0) }))
                    tls.send(AapMessage(1, false, 0, ByteBuffer.allocate(8).putLong(1000).array() + byteArrayOf(0,0,0,1,5)))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (sink.video.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                    assertFalse(sink.video.isEmpty())
                    sink.video.remove().done()
                    assertEquals(0x8004, tls.receive().type)
                    tls.send(AapMessage(0, false, 15, proto { writeEnum(1, 1) }))
                    assertEquals(16, tls.receive().type)
                }
            }
            try {
                val call = executor.submit {
                    session.execute(ProjectionEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)),
                        TlsTestIdentity.headUnit.client(TlsTestIdentity.phone.pin), ProjectionHeadUnit("test"))
                }
                phone.get(8, TimeUnit.SECONDS)
                call.get(3, TimeUnit.SECONDS)
                assertTrue(sink.closed)
            } finally { session.close(); executor.shutdownNow() }
        }
    }
}
