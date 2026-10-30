package com.github.lfdk.OAM.projection

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

data class ProjectionHeadUnit(val vehicleId: String, val softwareVersion: String = "1.0") {
    init {
        require(vehicleId.isNotBlank() && vehicleId.length <= 128)
        require(softwareVersion.length <= 64)
    }
}
internal data class ControlAction(val response: AapMessage?, val discovered: Boolean = false, val end: Boolean = false)

/** Control routing; the attached media profile supplies only implemented services. */
internal class AapControlProtocol(private val headUnit: ProjectionHeadUnit,
    private val media: ProjectionMediaProtocol? = null, private val sink: ProjectionMediaSink? = null) {
    fun handle(message: AapMessage): ControlAction {
        if (message.channel != 0 && !message.control) throw ProjectionProtocolException("Unadvertised application channel")
        fun reply(type: Int, body: ByteArray = byteArrayOf()) =
            AapMessage(message.channel, message.control, type, body)
        return when (message.type) {
            5 -> {
                val request = fields(message.body)
                for (field in listOf(4, 5)) {
                    val value = request[field] as? ByteArray ?: throw ProjectionProtocolException("Missing phone identity fields")
                    if (!ByteString.copyFrom(value).isValidUtf8) throw ProjectionProtocolException("Invalid phone identity encoding")
                }
                ControlAction(reply(6, discovery()), discovered = true)
            }
            11 -> {
                val timestamp = requiredNumber(message.body, 1)
                ControlAction(reply(12, encode { writeInt64(1, timestamp) }))
            }
            7 -> {
                requiredNumber(message.body, 1) // sint32 priority; only presence matters for this profile.
                val service = requiredNumber(message.body, 2)
                ControlAction(reply(8, encode { writeInt32(1, if (service == 0L || (service in 1..255 && media?.open(service.toInt()) == true)) 0 else -4) }))
            }
            15 -> {
                val reason = requiredNumber(message.body, 1)
                if (reason !in 1..5) throw ProjectionProtocolException("Invalid ByeBye reason")
                ControlAction(reply(16), end = true)
            }
            16 -> ControlAction(null, end = true)
            13 -> ControlAction(reply(14, encode { writeInt32(1, if (media != null) 2 else 1) })) // Native nav focus.
            18 -> ControlAction(reply(19, encode { writeInt32(1, sink?.audioFocus(numeric(message.body, 1, false).toInt()) ?: 3) })) // No audio sink: focus loss.
            20 -> ControlAction(reply(21, encode { writeBool(3, true) })) // No connected-device claims.
            17, 23, 24 -> { fields(message.body); ControlAction(null) }
            else -> throw ProjectionProtocolException("Unsupported control message: ${message.type}")
        }
    }

    fun byeBye(): AapMessage = AapMessage(0, false, 15, encode { writeInt32(1, 1) })

    private fun discovery(): ByteArray = encode {
        media?.services?.forEach { writeByteArray(1, it) }
        writeString(2, "OpenAuto")
        writeString(3, "Android head unit")
        writeString(4, "")
        writeString(5, headUnit.vehicleId)
        writeEnum(6, 3) // Unknown driver position, not an assumed left-hand-drive vehicle.
        writeString(7, "OpenAuto Multimedia")
        writeString(8, "Development receiver")
        writeString(9, if (media == null) "control-session" else "wireless-receiver")
        writeString(10, headUnit.softwareVersion)
        writeBool(11, false)
        writeString(14, "OpenAuto")
    }

    private fun requiredNumber(body: ByteArray, field: Int): Long =
        fields(body)[field] as? Long ?: throw ProjectionProtocolException("Missing required numeric field $field")

    private fun fields(body: ByteArray): Map<Int, Any> {
        if (body.size > 16384) throw ProjectionProtocolException("Oversized control payload")
        val input = CodedInputStream.newInstance(body)
        input.setSizeLimit(16384)
        input.setRecursionLimit(8)
        val values = mutableMapOf<Int, Any>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            when (tag and 7) {
                0 -> values[field] = input.readInt64()
                2 -> {
                    val bytes = input.readByteArray()
                    if (bytes.size > 1024) throw ProjectionProtocolException("Oversized control field")
                    values[field] = bytes
                }
                else -> if (!input.skipField(tag)) throw ProjectionProtocolException("Unexpected control group")
            }
            if (values.size > 64) throw ProjectionProtocolException("Too many control fields")
        }
        return values
    }

    private fun encode(block: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        CodedOutputStream.newInstance(output).apply { block(); flush() }
        return output.toByteArray()
    }
}
