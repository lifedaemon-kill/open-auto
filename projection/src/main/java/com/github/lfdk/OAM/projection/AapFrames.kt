package com.github.lfdk.OAM.projection

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ProjectionProtocolException(message: String) : IOException(message)

/** The clear AAP envelope. Encrypted payloads must be decrypted before message reassembly. */
data class AapFrame(
    val channel: Int,
    val flags: Int,
    val payload: ByteArray,
    val messageLength: Int? = null
) {
    val first: Boolean get() = flags and FIRST != 0
    val last: Boolean get() = flags and LAST != 0
    val encrypted: Boolean get() = flags and ENCRYPTED != 0

    companion object {
        const val FIRST = 1
        const val LAST = 2
        const val CONTROL = 4
        const val ENCRYPTED = 8
        const val COMPLETE = FIRST or LAST
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }
}

object AapFrameCodec {
    /** beforeRead must enforce the caller's overall deadline, including partial reads. */
    fun read(input: InputStream, beforeRead: () -> Unit = {}): AapFrame {
        val header = readExactly(input, 4, beforeRead)
        val channel = header[0].toInt() and 255
        val flags = header[1].toInt() and 255
        validateFlags(flags)
        val length = unsignedShort(header, 2)
        val hasTotal = flags and AapFrame.FIRST != 0 && flags and AapFrame.LAST == 0
        val total = if (hasTotal) {
            val bytes = readExactly(input, 4, beforeRead)
            val declared = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
            if (declared !in 2L..AapFrame.MAX_MESSAGE_BYTES.toLong()) {
                throw ProjectionProtocolException("Invalid AAP message length: $declared")
            }
            declared.toInt()
        } else null
        return AapFrame(channel, flags, readExactly(input, length, beforeRead), total)
    }

    fun write(output: OutputStream, frame: AapFrame) {
        require(frame.channel in 0..255)
        validateFlags(frame.flags)
        require(frame.payload.size <= 65535)
        val hasTotal = frame.first && !frame.last
        require(hasTotal == (frame.messageLength != null))
        val total = frame.messageLength
        if (total != null) require(total in 2..AapFrame.MAX_MESSAGE_BYTES)
        val header = ByteArray(if (hasTotal) 8 else 4)
        header[0] = frame.channel.toByte()
        header[1] = frame.flags.toByte()
        header[2] = (frame.payload.size ushr 8).toByte()
        header[3] = frame.payload.size.toByte()
        if (total != null) repeat(4) { index -> header[4 + index] = (total ushr (24 - index * 8)).toByte() }
        output.write(header)
        output.write(frame.payload)
        output.flush()
    }

    private fun validateFlags(flags: Int) {
        if (flags !in 0..15) throw ProjectionProtocolException("Unknown AAP frame flags: $flags")
    }

    private fun readExactly(input: InputStream, size: Int, beforeRead: () -> Unit): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            beforeRead()
            val count = input.read(data, offset, size - offset)
            if (count < 0) throw EOFException("AAP stream ended after $offset of $size bytes")
            if (count == 0) throw IOException("Input stream made no progress")
            offset += count
        }
        return data
    }

    internal fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
}

data class AapMessage(val channel: Int, val control: Boolean, val type: Int, val body: ByteArray)

/** Bounded, channel-aware reassembly of plaintext, including fragments splitting the type field. */
class AapMessageAssembler(private val memoryBudget: Int = 8 * 1024 * 1024) {
    private data class Pending(val bytes: ByteArray, var count: Int)
    private val pending = mutableMapOf<Int, Pending>()
    private var reserved = 0

    fun accept(frame: AapFrame): AapMessage? {
        if (frame.encrypted) throw ProjectionProtocolException("Decrypt each TLS frame before reassembly")
        val control = frame.flags and AapFrame.CONTROL != 0
        val key = frame.channel * 2 + if (control) 1 else 0
        if (frame.first) {
            if (key in pending) throw ProjectionProtocolException("Overlapping AAP messages on channel ${frame.channel}")
            if (frame.last) {
                if (frame.messageLength != null) throw ProjectionProtocolException("Unexpected total on complete frame")
                return message(frame.channel, control, frame.payload)
            }
            val total = frame.messageLength ?: throw ProjectionProtocolException("Missing fragmented message length")
            if (total !in 2..AapFrame.MAX_MESSAGE_BYTES || total > memoryBudget - reserved) {
                throw ProjectionProtocolException("AAP reassembly memory limit exceeded")
            }
            if (frame.payload.size > total) throw ProjectionProtocolException("First fragment exceeds declared length")
            val buffer = ByteArray(total)
            frame.payload.copyInto(buffer)
            pending[key] = Pending(buffer, frame.payload.size)
            reserved += total
            return null
        }
        if (frame.messageLength != null) throw ProjectionProtocolException("Unexpected total on continuation")
        val active = pending[key] ?: throw ProjectionProtocolException("Continuation without a first fragment")
        if (frame.payload.size > active.bytes.size - active.count) {
            throw ProjectionProtocolException("AAP fragments exceed declared length")
        }
        frame.payload.copyInto(active.bytes, active.count)
        active.count += frame.payload.size
        if (!frame.last) return null
        pending.remove(key)
        reserved -= active.bytes.size
        if (active.count != active.bytes.size) throw ProjectionProtocolException("Incomplete final AAP fragment")
        return message(frame.channel, control, active.bytes)
    }

    fun clear() {
        pending.clear()
        reserved = 0
    }

    private fun message(channel: Int, control: Boolean, bytes: ByteArray): AapMessage {
        if (bytes.size < 2) throw ProjectionProtocolException("Missing AAP message type")
        return AapMessage(channel, control, AapFrameCodec.unsignedShort(bytes, 0), bytes.copyOfRange(2, bytes.size))
    }
}
