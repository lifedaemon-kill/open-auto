package com.github.lfdk.OAM.projection

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

enum class ProbeStage { CONNECTING, NEGOTIATING, SECURING }

data class NegotiatedVersion(val major: Int, val minor: Int, val status: Int) {
    val accepted: Boolean get() = status != -1

    companion object {
        fun from(message: AapMessage): NegotiatedVersion {
            if (message.channel != 0 || message.type != 2 || message.body.size < 6) {
                throw ProjectionProtocolException("Invalid AAP version response")
            }
            val bytes = message.body
            val rawStatus = AapFrameCodec.unsignedShort(bytes, 4)
            return NegotiatedVersion(AapFrameCodec.unsignedShort(bytes, 0),
                AapFrameCodec.unsignedShort(bytes, 2), rawStatus.toShort().toInt())
        }
    }
}

data class SecureProbeResult(val version: NegotiatedVersion, val tls: ProjectionTlsResult)
class ProjectionVersionRejected : java.io.IOException("Peer refused protocol version")

/** Development probe: always closes the connection; neither result implies running projection. */
class ProjectionVersionProbe(private val timeoutMs: Int = 8000,
    acceptedChannel: java.nio.channels.SocketChannel? = null) : Closeable {
    init { require(timeoutMs in 100..30000) }
    internal val transport = AapTcpTransport(acceptedChannel)
    private val started = AtomicBoolean(false)

    fun execute(endpoint: ProjectionEndpoint, onStage: (ProbeStage) -> Unit = {}): NegotiatedVersion =
        exchange(endpoint, onStage) { it }

    /** TLS 1.2 and clear AuthComplete are sent on the SAME connection as version negotiation. */
    fun executeTls(endpoint: ProjectionEndpoint, client: ProjectionTlsClient,
        onStage: (ProbeStage) -> Unit = {}): SecureProbeResult =
        withTls(endpoint, client, onStage) { result, _ -> result }

    internal fun <T> withTls(endpoint: ProjectionEndpoint, client: ProjectionTlsClient,
        onStage: (ProbeStage) -> Unit, ready: (SecureProbeResult, AapTcpTransport) -> T): T {
        try {
            return exchange(endpoint, onStage) { version ->
                if (!version.accepted) throw ProjectionVersionRejected()
                transport.beginPhase(15000)
                onStage(ProbeStage.SECURING)
                val tls = try {
                    AapTlsHandshake(client.engine).execute(transport::readFrame, transport::writeFrame, transport::checkDeadline)
                } catch (failure: Exception) {
                    throw client.peerRejection() ?: failure
                }
                // Type 4 + protobuf status SUCCESS (field 1, value 0), outside TLS per AAP.
                transport.writeFrame(AapFrame(0, AapFrame.COMPLETE, byteArrayOf(0, 4, 8, 0)))
                ready(SecureProbeResult(version, tls), transport)
            }
        } finally {
            client.engine.closeOutbound()
        }
    }

    private fun <T> exchange(endpoint: ProjectionEndpoint, onStage: (ProbeStage) -> Unit,
        afterVersion: (NegotiatedVersion) -> T): T {
        check(started.compareAndSet(false, true)) { "A probe call can only run once" }
        transport.beginPhase(timeoutMs)
        try {
            onStage(ProbeStage.CONNECTING)
            transport.connect(endpoint)
            onStage(ProbeStage.NEGOTIATING)
            transport.writeFrame(AapFrame(0, AapFrame.COMPLETE, byteArrayOf(0, 1, 0, 1, 0, 2)))
            val assembler = AapMessageAssembler(memoryBudget = 64 * 1024)
            var consumed = 0
            repeat(32) {
                val frame = transport.readFrame()
                consumed += frame.payload.size
                if (consumed > 64 * 1024) throw ProjectionProtocolException("Excess data before version response")
                if (frame.encrypted) throw ProjectionProtocolException("Encrypted data before version negotiation")
                val message = assembler.accept(frame) ?: return@repeat
                if (message.channel == 0 && message.type == 2) return afterVersion(NegotiatedVersion.from(message))
                if (message.channel == 0 && message.type in listOf(15, 16)) {
                    throw ProjectionProtocolException("Peer ended version negotiation")
                }
            }
            throw ProjectionProtocolException("Too many messages before version response")
        } finally {
            close()
        }
    }

    override fun close() = transport.close()
}
