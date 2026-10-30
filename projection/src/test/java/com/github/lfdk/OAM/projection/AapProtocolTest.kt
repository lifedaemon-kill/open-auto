package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class AapProtocolTest {
    private fun encoded(vararg frames: AapFrame): ByteArray = ByteArrayOutputStream().also { output ->
        frames.forEach { AapFrameCodec.write(output, it) }
    }.toByteArray()

    @Test fun readsSplitTcpHeadersAndPreservesFollowingFrame() {
        val bytes = encoded(AapFrame(0, 3, byteArrayOf(0, 2, 1)),
            AapFrame(7, 3, byteArrayOf(0, 0, 9)))
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 1))
        }
        assertArrayEquals(byteArrayOf(0, 2, 1), AapFrameCodec.read(input).payload)
        val second = AapFrameCodec.read(input)
        assertEquals(7, second.channel)
        assertArrayEquals(byteArrayOf(0, 0, 9), second.payload)
        assertEquals(-1, input.read())
    }

    @Test fun firstFragmentTotalIsOutsidePayloadLength() {
        val bytes = encoded(AapFrame(3, AapFrame.FIRST, byteArrayOf(0), 5))
        assertArrayEquals(byteArrayOf(3, 1, 0, 1, 0, 0, 0, 5, 0), bytes)
        val decoded = AapFrameCodec.read(ByteArrayInputStream(bytes))
        assertEquals(5, decoded.messageLength)
        assertArrayEquals(byteArrayOf(0), decoded.payload)
    }

    @Test fun rejectsEveryTruncatedHeaderAndBody() {
        val frame = encoded(AapFrame(0, 3, byteArrayOf(0, 2, 3)))
        for (length in frame.indices) {
            assertThrows(EOFException::class.java) {
                AapFrameCodec.read(ByteArrayInputStream(frame.copyOf(length)))
            }
        }
    }

    @Test fun rejectsUnknownFlagsAndUnboundedDeclaredLength() {
        assertThrows(ProjectionProtocolException::class.java) {
            AapFrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 16, 0, 0)))
        }
        assertThrows(ProjectionProtocolException::class.java) {
            AapFrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 1, 0, 0, -1, -1, -1, -1)))
        }
    }

    @Test fun refusesNonProgressingStream() {
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertThrows(IOException::class.java) { AapFrameCodec.read(input) }
    }

    @Test fun assemblesSplitTypeWithEmptyContinuationsAndInterleavedChannels() {
        val assembler = AapMessageAssembler()
        assertNull(assembler.accept(AapFrame(1, AapFrame.FIRST, byteArrayOf(-128), 4)))
        assertNull(assembler.accept(AapFrame(2, AapFrame.FIRST, byteArrayOf(0, 9), 3)))
        assertNull(assembler.accept(AapFrame(1, 0, byteArrayOf())))
        val second = assembler.accept(AapFrame(2, AapFrame.LAST, byteArrayOf(7)))!!
        assertEquals(9, second.type)
        assertArrayEquals(byteArrayOf(7), second.body)
        val first = assembler.accept(AapFrame(1, AapFrame.LAST, byteArrayOf(1, 4, 5)))!!
        assertEquals(0x8001, first.type)
        assertArrayEquals(byteArrayOf(4, 5), first.body)
    }

    @Test fun keepsControlAndMediaFragmentsSeparateOnSameChannel() {
        val assembler = AapMessageAssembler()
        assembler.accept(AapFrame(1, AapFrame.FIRST, byteArrayOf(0), 2))
        assembler.accept(AapFrame(1, AapFrame.FIRST or AapFrame.CONTROL, byteArrayOf(0), 2))
        assertTrue(assembler.accept(AapFrame(1, AapFrame.LAST or AapFrame.CONTROL, byteArrayOf(8)))!!.control)
        assertFalse(assembler.accept(AapFrame(1, AapFrame.LAST, byteArrayOf(9)))!!.control)
    }

    @Test fun rejectsOrphanOverlappingAndMisSizedFragments() {
        assertThrows(ProjectionProtocolException::class.java) {
            AapMessageAssembler().accept(AapFrame(0, AapFrame.LAST, byteArrayOf(1)))
        }
        val overlap = AapMessageAssembler()
        overlap.accept(AapFrame(0, AapFrame.FIRST, byteArrayOf(0), 2))
        assertThrows(ProjectionProtocolException::class.java) { overlap.accept(AapFrame(0, 3, byteArrayOf(0, 2))) }
        val short = AapMessageAssembler()
        short.accept(AapFrame(0, AapFrame.FIRST, byteArrayOf(0), 4))
        assertThrows(ProjectionProtocolException::class.java) { short.accept(AapFrame(0, AapFrame.LAST, byteArrayOf(2))) }
        val long = AapMessageAssembler()
        long.accept(AapFrame(0, AapFrame.FIRST, byteArrayOf(0), 2))
        assertThrows(ProjectionProtocolException::class.java) { long.accept(AapFrame(0, AapFrame.LAST, byteArrayOf(2, 3))) }
    }

    @Test fun enforcesAggregateMemoryBudgetAndReleasesItAfterCompletion() {
        val assembler = AapMessageAssembler(memoryBudget = 4)
        assembler.accept(AapFrame(0, AapFrame.FIRST, byteArrayOf(0), 4))
        assertThrows(ProjectionProtocolException::class.java) {
            assembler.accept(AapFrame(1, AapFrame.FIRST, byteArrayOf(0), 2))
        }
        assembler.accept(AapFrame(0, AapFrame.LAST, byteArrayOf(2, 3, 4)))
        assertNull(assembler.accept(AapFrame(1, AapFrame.FIRST, byteArrayOf(0), 4)))
        assembler.clear()
        assertNull(assembler.accept(AapFrame(2, AapFrame.FIRST, byteArrayOf(0), 4)))
    }

    @Test fun refusesCiphertextBeforeMessageAssembly() {
        assertThrows(ProjectionProtocolException::class.java) {
            AapMessageAssembler().accept(AapFrame(0, 3 or AapFrame.ENCRYPTED, byteArrayOf(0, 2)))
        }
    }

    @Test fun readsSignedVersionStatusWithoutTreatingOtherStatusesAsRejection() {
        fun version(status: ByteArray) = NegotiatedVersion.from(
            AapMessage(0, false, 2, byteArrayOf(0, 1, 0, 6) + status))
        assertFalse(version(byteArrayOf(-1, -1)).accepted)
        assertEquals(-1, version(byteArrayOf(-1, -1)).status)
        assertTrue(version(byteArrayOf(0, 6)).accepted)
        assertEquals(6, version(byteArrayOf(0, 0)).minor)
        assertThrows(ProjectionProtocolException::class.java) {
            NegotiatedVersion.from(AapMessage(0, false, 11, ByteArray(6)))
        }
    }

    @Test fun validatesNumericLocalAddressesWithoutDns() {
        assertEquals(5277, ProjectionEndpoint.parse("192.168.43.1").address.port)
        assertEquals("10.0.0.7", ProjectionEndpoint.parse("10.0.0.7").address.address.hostAddress)
        listOf("localhost", "8.8.8.8", "192.168.1.256", "192.168.01.1", "https://192.168.1.1",
            "172.32.0.1", "192.168.1.255", "192.168.1").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { ProjectionEndpoint.parse(host) }
        }
    }
}
