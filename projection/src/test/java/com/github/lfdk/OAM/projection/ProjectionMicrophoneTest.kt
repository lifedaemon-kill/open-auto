package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

private class FakeMicrophone : ProjectionMicrophoneSource {
    lateinit var ready: (Boolean) -> Unit
    lateinit var data: (ByteArray, Long) -> Unit
    lateinit var failed: () -> Unit
    var stops = 0
    override fun start(ready: (Boolean) -> Unit, data: (ByteArray, Long) -> Unit, failed: () -> Unit) {
        this.ready = ready; this.data = data; this.failed = failed
    }
    override fun stop() { stops++ }
    override fun close() {}
}

class ProjectionMicrophoneTest {
    private fun open(window: Int = 1) = AapMessage(6, false, 0x8005,
        proto { writeBool(1, true); writeUInt32(4, window) })
    private fun ack(id: Int, count: Int) = AapMessage(6, false, 0x8004,
        proto { writeInt32(1, id); writeUInt32(2, count) })

    @Test fun openResponsePrecedesDataAndAckReleasesOnlyClaimedCredits() {
        val source = FakeMicrophone()
        val queued = mutableListOf<ProjectionOutbound>()
        val protocol = ProjectionMicrophoneProtocol(source) { queued += it }
        protocol.handle(open())
        source.data(byteArrayOf(1, 2), 123)
        assertTrue(queued.isEmpty())
        source.ready(true)
        assertEquals(listOf(0x8006, 0), queued.map { it.message.type })
        assertEquals(1L, numeric(queued[0].message.body, 2))
        assertEquals(0L, numeric(queued[0].message.body, 1))
        assertThrows(ProjectionProtocolException::class.java) { protocol.handle(ack(1, 1)) }
        assertTrue(queued[1].claim())
        assertEquals(123L, ByteBuffer.wrap(queued[1].message.body).long)
        source.data(byteArrayOf(3, 4), 200)
        assertEquals(2, queued.size)
        protocol.handle(ack(1, 1))
        assertEquals(3, queued.size)
        assertTrue(queued.last().claim())
        protocol.close()
    }

    @Test fun closeRetiresQueuedFramesAndLateCallbacksBeforeReopen() {
        val source = FakeMicrophone()
        val queued = mutableListOf<ProjectionOutbound>()
        val protocol = ProjectionMicrophoneProtocol(source) { queued += it }
        protocol.handle(open())
        source.ready(true)
        source.data(byteArrayOf(1, 2), 1)
        val oldData = source.data
        val oldPacket = queued.last()
        protocol.handle(AapMessage(6, false, 0x8005, proto { writeBool(1, false) }))
        assertFalse(oldPacket.claim())
        protocol.handle(open())
        source.ready(true)
        val size = queued.size
        oldData(byteArrayOf(1, 2), 2)
        assertEquals(size, queued.size)
        protocol.handle(ack(1, 100)) // An old session cannot release the replacement's window.
        source.data(byteArrayOf(3, 4), 3)
        assertTrue(queued.last().claim())
        assertEquals(2L, numeric(queued[size - 1].message.body, 2))
        protocol.close()
    }

    @Test fun stalledPeerEndsCaptureWithBoundedBacklog() {
        val source = FakeMicrophone()
        val queued = mutableListOf<ProjectionOutbound>()
        val protocol = ProjectionMicrophoneProtocol(source) { queued += it }
        protocol.handle(open()); source.ready(true)
        repeat(6) { source.data(byteArrayOf(1, 2), it.toLong()) }
        assertEquals(0x8006, queued.last().message.type)
        assertEquals(-1L, numeric(queued.last().message.body, 1))
        assertTrue(source.stops > 0)
        assertFalse(queued[1].claim())
    }

    @Test fun omittedOptionalWindowUsesTwoCreditsAndStopRetiresCapture() {
        val source = FakeMicrophone()
        val queued = mutableListOf<ProjectionOutbound>()
        val protocol = ProjectionMicrophoneProtocol(source) { queued += it }
        protocol.handle(AapMessage(6, false, 0x8000, proto { writeEnum(1, 1) }))
        assertEquals(0x8003, queued.single().message.type)
        protocol.handle(AapMessage(6, false, 0x8005, proto { writeBool(1, true) }))
        source.ready(true)
        repeat(3) { source.data(byteArrayOf(1, 2), it.toLong()) }
        assertEquals(2, queued.count { it.message.type == 0 })
        protocol.handle(AapMessage(6, false, 0x8002, byteArrayOf()))
        assertTrue(source.stops > 0)
        queued.filter { it.message.type == 0 }.forEach { assertFalse(it.claim()) }
        protocol.close()
    }

    @Test fun microphoneIsOfferedOnlyWithAnAttachedSource() {
        val microphone = ProjectionMicrophoneProtocol(FakeMicrophone()) {}
        val disabled = ProjectionMediaProtocol(RecordingMediaSink()) {}
        val enabled = ProjectionMediaProtocol(RecordingMediaSink(), microphone) {}
        assertFalse(disabled.open(6))
        assertTrue(enabled.open(6))
        assertEquals(7, enabled.services.size)
        microphone.close()
    }

    @Test fun recorderFailureRepliesWithoutAnyData() {
        val source = FakeMicrophone()
        val queued = mutableListOf<ProjectionOutbound>()
        val protocol = ProjectionMicrophoneProtocol(source) { queued += it }
        protocol.handle(open()); source.ready(false)
        assertEquals(1, queued.size)
        assertEquals(-1L, numeric(queued.single().message.body, 1))
        assertTrue(source.stops > 0)
        protocol.close()
    }
}
