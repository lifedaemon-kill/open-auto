package com.github.lfdk.OAM.projection

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun wire(block: CodedOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
    CodedOutputStream.newInstance(it).apply { block(); flush() }
}.toByteArray()

internal fun numeric(body: ByteArray, field: Int, required: Boolean = true, defaultValue: Long = 0): Long {
    if (body.size > 16384) throw ProjectionProtocolException("Oversized service control")
    val input = CodedInputStream.newInstance(body)
    input.setRecursionLimit(8)
    var result: Long? = null
    while (!input.isAtEnd) {
        val tag = input.readTag()
        if (tag == (field shl 3)) result = input.readInt64()
        else if (!input.skipField(tag)) throw ProjectionProtocolException("Invalid service payload")
    }
    return result ?: if (!required) defaultValue else throw ProjectionProtocolException("Missing service field $field")
}

data class ProjectionPointer(val id: Int, val x: Int, val y: Int)
data class ProjectionTouch(val timestampNanos: Long, val action: Int, val actionIndex: Int, val pointers: List<ProjectionPointer>) {
    fun message(): AapMessage {
        require(action in 0..6 && pointers.size in 1..10 && actionIndex in pointers.indices)
        require(pointers.map { it.id }.distinct().size == pointers.size)
        require(pointers.all { it.id >= 0 && it.x in 0..799 && it.y in 0..479 })
        return AapMessage(2, false, 0x8001, wire {
            writeUInt64(1, timestampNanos)
            writeByteArray(3, wire {
                pointers.forEach { pointer -> writeByteArray(1, wire {
                    writeUInt32(1, pointer.x); writeUInt32(2, pointer.y); writeUInt32(3, pointer.id)
                }) }
                writeUInt32(2, actionIndex); writeEnum(3, action)
            })
        })
    }
}

/** Sink callbacks must enqueue bounded work; completed returns media credits after consumption. */
interface ProjectionMediaSink : Closeable {
    val microphone: ProjectionMicrophoneSource? get() = null
    fun video(bytes: ByteArray, timestampUs: Long, codecConfig: Boolean, completed: () -> Unit)
    fun audio(channel: Int, bytes: ByteArray, timestampUs: Long, completed: () -> Unit)
    fun stop(channel: Int)
    fun audioFocus(request: Int): Int
}

internal class ProjectionMediaProtocol(
    private val sink: ProjectionMediaSink,
    private val microphone: ProjectionMicrophoneProtocol? = null,
    private val enqueue: (AapMessage) -> Unit
) {
    private val opened = mutableSetOf<Int>()
    private val configured = mutableSetOf<Int>()
    private val sessions = mutableMapOf<Int, Int>()
    @Volatile var inputReady = false
        private set

    val services: List<ByteArray> = listOf(
        wire { writeUInt32(1, 1); writeByteArray(3, wire {
            writeEnum(1, 3)
            writeByteArray(4, wire {
                writeEnum(1, 1); writeEnum(2, 2); writeUInt32(3, 0); writeUInt32(4, 0)
                writeUInt32(5, 160); writeEnum(10, 3)
            })
        }) },
        wire { writeUInt32(1, 2); writeByteArray(4, wire {
            writeByteArray(2, wire { writeUInt32(1, 800); writeUInt32(2, 480) })
        }) },
        audioService(3, 3, 48000, 2),
        audioService(4, 2, 16000, 1),
        audioService(5, 1, 16000, 1),
        wire { writeUInt32(1, 7); writeByteArray(2, wire { writeByteArray(1, wire { writeEnum(1, 10) }) }) }
    ) + if (microphone != null) listOf(wire {
        writeUInt32(1, 6); writeByteArray(5, wire {
            writeEnum(1, 1)
            writeByteArray(2, wire { writeUInt32(1, 16000); writeUInt32(2, 16); writeUInt32(3, 1) })
        })
    }) else emptyList()

    fun open(id: Int): Boolean {
        if (id == 6 && microphone != null) { opened += id; return true }
        if (id !in listOf(1, 2, 3, 4, 5, 7)) return id == 0
        opened += id
        return true
    }

    fun handle(message: AapMessage): List<AapMessage> {
        val channel = message.channel
        if (channel !in opened) throw ProjectionProtocolException("Service channel was not opened")
        fun reply(type: Int, body: ByteArray) = AapMessage(channel, false, type, body)
        if (channel == 6) { microphone!!.handle(message); return emptyList() }
        if (channel == 2) {
            if (message.type != 0x8002) throw ProjectionProtocolException("Unsupported input message")
            inputReady = true
            return listOf(reply(0x8003, wire { writeInt32(1, 0) }))
        }
        if (channel == 7) {
            if (message.type != 0x8001) throw ProjectionProtocolException("Unsupported sensor request")
            val type = numeric(message.body, 1)
            return if (type == 10L) listOf(reply(0x8002, wire { writeInt32(1, 0) }),
                reply(0x8003, wire { writeByteArray(10, wire { writeBool(1, true) }) }))
            else listOf(reply(0x8002, wire { writeInt32(1, -9) }))
        }
        return when (message.type) {
            0x8000 -> {
                val expected = if (channel == 1) 3L else 1L
                if (numeric(message.body, 1) != expected) throw ProjectionProtocolException("Unsupported media codec")
                configured += channel
                val replies = mutableListOf(reply(0x8003, wire {
                    writeEnum(1, 2); writeUInt32(2, 4); writeUInt32(3, 0)
                }))
                if (channel == 1) replies += reply(0x8008, wire { writeEnum(1, 1); writeBool(2, true) })
                replies
            }
            0x8001 -> {
                if (channel !in configured || numeric(message.body, 2) != 0L) throw ProjectionProtocolException("Invalid media configuration")
                val id = numeric(message.body, 1)
                if (id !in 0..Int.MAX_VALUE.toLong()) throw ProjectionProtocolException("Invalid media session")
                sessions[channel] = id.toInt()
                emptyList()
            }
            0x8002 -> {
                sessions.remove(channel); sink.stop(channel); emptyList()
            }
            0x8007 -> {
                if (channel != 1) throw ProjectionProtocolException("Video focus on non-video channel")
                val mode = numeric(message.body, 2)
                listOf(reply(0x8008, wire { writeEnum(1, if (mode == 1L || mode == 4L) 1 else 2); writeBool(2, false) }))
            }
            0, 1 -> {
                val session = sessions[channel]
                if (message.type == 0 && session == null) throw ProjectionProtocolException("Media data before Start")
                if (channel !in configured) throw ProjectionProtocolException("Media data before Setup")
                val timestamp = if (message.type == 0) {
                    if (message.body.size < 8) throw ProjectionProtocolException("Truncated media timestamp")
                    ByteBuffer.wrap(message.body, 0, 8).order(ByteOrder.BIG_ENDIAN).long
                } else 0L
                if (timestamp < 0) throw ProjectionProtocolException("Invalid media timestamp")
                val data = message.body.copyOfRange(if (message.type == 0) 8 else 0, message.body.size)
                if (data.isEmpty()) throw ProjectionProtocolException("Empty media packet")
                val completed = {
                    if (message.type == 0) enqueue(reply(0x8004, wire {
                        writeInt32(1, session!!); writeUInt32(2, 1)
                    }))
                    Unit
                }
                if (channel == 1) sink.video(data, timestamp, message.type == 1, completed)
                else {
                    if (message.type != 0 || data.size % (if (channel == 3) 4 else 2) != 0) {
                        throw ProjectionProtocolException("Invalid PCM audio data")
                    }
                    sink.audio(channel, data, timestamp, completed)
                }
                emptyList()
            }
            else -> throw ProjectionProtocolException("Unsupported media message")
        }
    }

    private fun audioService(id: Int, stream: Int, rate: Int, channels: Int) = wire {
        writeUInt32(1, id)
        writeByteArray(3, wire {
            writeEnum(1, 1); writeEnum(2, stream)
            writeByteArray(3, wire { writeUInt32(1, rate); writeUInt32(2, 16); writeUInt32(3, channels) })
            writeBool(5, true)
        })
    }
}
