package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.protobuf.CodedInputStream

internal class RecordingMediaSink : ProjectionMediaSink {
    data class Video(val bytes: ByteArray, val pts: Long, val config: Boolean, val done: () -> Unit)
    val video = ConcurrentLinkedQueue<Video>()
    val audio = ConcurrentLinkedQueue<ByteArray>()
    val audioDone = ConcurrentLinkedQueue<() -> Unit>()
    var closed = false
    override fun video(bytes: ByteArray, timestampUs: Long, codecConfig: Boolean, completed: () -> Unit) {
        video += Video(bytes, timestampUs, codecConfig, completed)
    }
    override fun audio(channel: Int, bytes: ByteArray, timestampUs: Long, completed: () -> Unit) {
        audio += bytes; audioDone += completed
    }
    override fun stop(channel: Int) {}
    override fun audioFocus(request: Int) = 1
    override fun close() { closed = true }
}

class ProjectionMediaProtocolTest {
    private fun setup(protocol: ProjectionMediaProtocol, channel: Int) {
        assertTrue(protocol.open(channel))
        protocol.handle(AapMessage(channel, false, 0x8000, proto { writeUInt32(1, if (channel == 1) 3 else 1) }))
        protocol.handle(AapMessage(channel, false, 0x8001, proto { writeInt32(1, 8); writeUInt32(2, 0) }))
    }

    @Test fun videoAckWaitsForConsumptionAndCsdDoesNotConsumeCredits() {
        val sink = RecordingMediaSink()
        val replies = mutableListOf<AapMessage>()
        val protocol = ProjectionMediaProtocol(sink) { replies += it }
        setup(protocol, 1)
        protocol.handle(AapMessage(1, false, 1, byteArrayOf(0, 0, 0, 1, 7)))
        sink.video.remove().done()
        assertTrue(replies.isEmpty())
        val bytes = byteArrayOf(0, 0, 0, 1, 5)
        protocol.handle(AapMessage(1, false, 0, ByteBuffer.allocate(8).putLong(12345).array() + bytes))
        assertTrue(replies.isEmpty())
        val packet = sink.video.remove()
        assertEquals(12345L, packet.pts)
        assertArrayEquals(bytes, packet.bytes)
        packet.done()
        assertEquals(0x8004, replies.single().type)
        assertEquals(8L, numbers(replies.single().body)[1])
        assertEquals(1L, numbers(replies.single().body)[2])
    }

    @Test fun pcmAlignmentAndOrderingAreValidated() {
        val sink = RecordingMediaSink()
        val protocol = ProjectionMediaProtocol(sink) {}
        assertThrows(ProjectionProtocolException::class.java) { protocol.handle(AapMessage(3, false, 0, ByteArray(12))) }
        setup(protocol, 3)
        assertThrows(ProjectionProtocolException::class.java) { protocol.handle(AapMessage(3, false, 0, ByteArray(11))) }
        protocol.handle(AapMessage(3, false, 0, ByteArray(12)))
        assertEquals(4, sink.audio.single().size)
    }

    @Test fun descriptorsOfferOnlyImplementedCodecsAndTouch() {
        val protocol = ProjectionMediaProtocol(RecordingMediaSink()) {}
        val ids = protocol.services.map { descriptor ->
            val input = CodedInputStream.newInstance(descriptor)
            assertEquals(8, input.readTag()); input.readUInt32()
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 7), ids)
        assertFalse(protocol.open(99))
    }

    @Test fun setupRejectsMismatchedCodec() {
        val protocol = ProjectionMediaProtocol(RecordingMediaSink()) {}
        protocol.open(1)
        assertThrows(ProjectionProtocolException::class.java) {
            protocol.handle(AapMessage(1, false, 0x8000, proto { writeUInt32(1, 7) }))
        }
    }

    @Test fun touchscreenBindingAndCoordinateBounds() {
        val protocol = ProjectionMediaProtocol(RecordingMediaSink()) {}
        assertFalse(protocol.inputReady)
        protocol.open(2)
        assertEquals(0x8003, protocol.handle(AapMessage(2, false, 0x8002, byteArrayOf())).single().type)
        assertTrue(protocol.inputReady)
        val touch = ProjectionTouch(1000000, 0, 0, listOf(ProjectionPointer(0, 799, 479))).message()
        assertEquals(2, touch.channel)
        assertEquals(0x8001, touch.type)
        assertEquals(1000000L, numbers(touch.body)[1])
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTouch(1, 0, 0, listOf(ProjectionPointer(0, 800, 1))).message()
        }
    }

    @Test fun enabledProfileAdvertisesMediaAndOpensKnownChannels() {
        val sink = RecordingMediaSink()
        val media = ProjectionMediaProtocol(sink) {}
        val control = AapControlProtocol(ProjectionHeadUnit("test"), media, sink)
        val response = control.handle(AapMessage(0, false, 5, proto { writeString(4, "Phone"); writeString(5, "Brand") })).response!!
        val input = CodedInputStream.newInstance(response.body)
        var count = 0
        while (!input.isAtEnd) { val tag = input.readTag(); if (tag == 10) { count++; input.readByteArray() } else input.skipField(tag) }
        assertEquals(6, count)
        val open = control.handle(AapMessage(1, true, 7, proto { writeSInt32(1, 0); writeInt32(2, 1) })).response!!
        assertEquals(0L, numbers(open.body)[1])
        assertEquals(1L, numbers(control.handle(AapMessage(0, false, 18, proto { writeEnum(1, 1) })).response!!.body)[1])
    }
}
