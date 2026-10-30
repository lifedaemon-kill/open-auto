package com.github.lfdk.OAM.projection

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

internal fun proto(block: CodedOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { out -> CodedOutputStream.newInstance(out).apply { block(); flush() } }.toByteArray()

internal fun numbers(bytes: ByteArray): Map<Int, Long> {
    val input = CodedInputStream.newInstance(bytes)
    val result = mutableMapOf<Int, Long>()
    while (!input.isAtEnd) {
        val tag = input.readTag()
        if (tag and 7 == 0) result[tag ushr 3] = input.readInt64() else input.skipField(tag)
    }
    return result
}

class AapControlProtocolTest {
    private val control = AapControlProtocol(ProjectionHeadUnit("unit-test"))
    @Test fun echoesEveryInt64PingTimestamp() {
        for (timestamp in listOf(0L, 1L, Long.MAX_VALUE, -1L)) {
            val response = control.handle(AapMessage(0, false, 11, proto { writeInt64(1, timestamp) })).response!!
            assertEquals(12, response.type)
            assertEquals(timestamp, numbers(response.body)[1])
        }
    }

    @Test fun discoveryHasAllRequiredIdentityFieldsAndNoMediaServices() {
        val response = control.handle(AapMessage(0, false, 5,
            proto { writeString(4, "Phone"); writeString(5, "Brand") }))
        assertTrue(response.discovered)
        assertEquals(6, response.response!!.type)
        val input = CodedInputStream.newInstance(response.response.body)
        val fields = mutableSetOf<Int>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            fields += tag ushr 3
            input.skipField(tag)
        }
        assertFalse(1 in fields)
        assertTrue(fields.containsAll((2..11).toList()))
    }

    @Test fun rejectsUnimplementedServicesWithoutPretendingTheyOpened() {
        val response = control.handle(AapMessage(3, true, 7,
            proto { writeSInt32(1, 0); writeInt32(2, 3) })).response!!
        assertEquals(3, response.channel)
        assertTrue(response.control)
        assertEquals(8, response.type)
        assertEquals(-4L, numbers(response.body)[1])
    }

    @Test fun cannotUseAnUnadvertisedMediaChannel() {
        assertThrows(ProjectionProtocolException::class.java) {
            control.handle(AapMessage(3, false, 0, byteArrayOf()))
        }
    }

    @Test fun malformedAndMissingRequiredFieldsFailClosed() {
        assertThrows(ProjectionProtocolException::class.java) { control.handle(AapMessage(0, false, 11, byteArrayOf())) }
        assertThrows(ProjectionProtocolException::class.java) {
            control.handle(AapMessage(0, false, 5, proto { writeInt32(4, 1); writeString(5, "Brand") }))
        }
        assertThrows(ProjectionProtocolException::class.java) {
            control.handle(AapMessage(0, false, 5, proto { writeByteArray(4, byteArrayOf(-1)); writeString(5, "Brand") }))
        }
        assertThrows(java.io.IOException::class.java) { control.handle(AapMessage(0, false, 11, byteArrayOf(8, -128))) }
        assertThrows(ProjectionProtocolException::class.java) { control.handle(AapMessage(0, false, 11, ByteArray(16385))) }
    }

    @Test fun nativeFocusDoesNotClaimAWorkingAudioOrNavSink() {
        assertEquals(1L, numbers(control.handle(AapMessage(0, false, 13, byteArrayOf())).response!!.body)[1])
        assertEquals(3L, numbers(control.handle(AapMessage(0, false, 18, byteArrayOf())).response!!.body)[1])
    }

    @Test fun byeByeIsAcknowledgedAndEndsTheSession() {
        val action = control.handle(AapMessage(0, false, 15, proto { writeEnum(1, 1) }))
        assertTrue(action.end)
        assertEquals(16, action.response!!.type)
        assertTrue(action.response.body.isEmpty())
    }
}
