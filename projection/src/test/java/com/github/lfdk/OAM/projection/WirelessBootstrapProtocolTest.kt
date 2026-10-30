package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import com.google.protobuf.CodedInputStream

class WirelessBootstrapProtocolTest {
    private val network = WirelessNetwork("DIRECT-OA-test", "temporary-secret", "", "192.168.49.1")

    @Test fun framingIsBigEndianAndExactForLargePayloads() {
        val output = ByteArrayOutputStream()
        WirelessMessageCodec.write(output, WirelessMessage(8, ByteArray(300) { it.toByte() }))
        val bytes = output.toByteArray()
        assertArrayEquals(byteArrayOf(1, 44, 0, 8), bytes.copyOfRange(0, 4))
        val decoded = WirelessMessageCodec.read(ByteArrayInputStream(bytes))
        assertEquals(8, decoded.type)
        assertArrayEquals(bytes.copyOfRange(4, bytes.size), decoded.body)
        assertThrows(EOFException::class.java) { WirelessMessageCodec.read(ByteArrayInputStream(bytes.copyOf(12))) }
        assertThrows(ProjectionProtocolException::class.java) {
            WirelessMessageCodec.read(ByteArrayInputStream(byteArrayOf(0x7f, -1, 0, 8)))
        }
    }

    @Test fun startHasReachableHeadUnitEndpointAndCredentialsHaveAllRequiredFields() {
        val protocol = WirelessBootstrapProtocol(network)
        val start = protocol.start()
        assertEquals(1, start.type)
        val startInput = CodedInputStream.newInstance(start.body)
        assertEquals(10, startInput.readTag()); assertEquals("192.168.49.1", startInput.readStringRequireUtf8())
        assertEquals(5288L, numeric(start.body, 2))
        val credentials = protocol.respond(WirelessMessage(2, byteArrayOf()))!!
        assertEquals(3, credentials.type)
        val input = CodedInputStream.newInstance(credentials.body)
        val fields = mutableSetOf<Int>()
        while (!input.isAtEnd) { val tag = input.readTag(); fields += tag ushr 3; input.skipField(tag) }
        assertEquals(setOf(1, 2, 3, 4, 5), fields)
        assertEquals(8L, numeric(credentials.body, 4))
        assertFalse(network.toString().contains(network.passphrase))
    }

    @Test fun pingIsEchoedAndPhoneFailureIsReported() {
        val protocol = WirelessBootstrapProtocol(network)
        val ping = byteArrayOf(8, 123)
        val reply = protocol.respond(WirelessMessage(8, ping))!!
        assertEquals(9, reply.type); assertArrayEquals(ping, reply.body)
        assertThrows(ProjectionProtocolException::class.java) {
            protocol.respond(WirelessMessage(6, proto { writeInt32(1, -8) }))
        }
        assertThrows(ProjectionProtocolException::class.java) {
            protocol.respond(WirelessMessage(7, proto { writeInt32(3, -1) }))
        }
        assertNull(protocol.respond(WirelessMessage(6, proto { writeInt32(1, 0) })))
    }
}
