package com.github.lfdk.OAM.projection

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Credentials are session-only and deliberately absent from toString/log output. */
class WirelessNetwork(
    val ssid: String, val passphrase: String, val bssid: String, val address: String, val port: Int = 5288
) {
    init {
        require(ssid.toByteArray(Charsets.UTF_8).size in 1..32)
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 })
        require(bssid.isEmpty() || bssid.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
        ProjectionEndpoint.parse(address)
        require(port in 1024..65535)
    }
    override fun toString() = "WirelessNetwork(redacted)"
}

data class WirelessMessage(val type: Int, val body: ByteArray)

/** WPP: payload length BE16, type BE16, then bounded protobuf payload. */
object WirelessMessageCodec {
    private const val MAX_BODY = 16384
    fun read(input: InputStream): WirelessMessage {
        val stream = DataInputStream(input)
        val size = stream.readUnsignedShort()
        val type = stream.readUnsignedShort()
        if (size > MAX_BODY) throw ProjectionProtocolException("Oversized wireless handshake")
        return WirelessMessage(type, ByteArray(size).also(stream::readFully))
    }
    fun write(output: OutputStream, message: WirelessMessage) {
        require(message.body.size <= MAX_BODY && message.type in 0..65535)
        DataOutputStream(output).apply {
            writeShort(message.body.size); writeShort(message.type); write(message.body); flush()
        }
    }
}

/** Legacy WPP bootstrap: no unimplemented TCP-reconnect endpoint is stored on the phone. */
class WirelessBootstrapProtocol(private val network: WirelessNetwork) {
    fun start() = WirelessMessage(1, wire {
        writeString(1, network.address); writeInt32(2, network.port); writeEnum(3, 0)
    })

    fun respond(message: WirelessMessage): WirelessMessage? = when (message.type) {
        2 -> WirelessMessage(3, wire {
            writeString(1, network.ssid); writeString(2, network.passphrase)
            writeString(3, network.bssid); writeEnum(4, 8); writeEnum(5, 1)
        })
        6, 7 -> {
            val status = numeric(message.body, if (message.type == 6) 1 else 3, false)
            if (status != 0L) throw ProjectionProtocolException("Phone refused wireless connection: $status")
            null
        }
        8 -> WirelessMessage(9, message.body.copyOf())
        1, 5, 9 -> null
        else -> throw ProjectionProtocolException("Unsupported wireless handshake message")
    }
}
