package com.github.lfdk.OAM.projection

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

internal class ProjectionTlsClosed : java.io.IOException("Peer closed TLS")

/** Single IO owner. Preserves TLS record order; decrypts every envelope before AAP reassembly. */
internal class AapTlsChannel(
    private val engine: SSLEngine,
    private val read: () -> AapFrame,
    private val write: (AapFrame) -> Unit,
    private val check: () -> Unit
) {
    private val assembler = AapMessageAssembler(8 * 1024 * 1024)
    private val network = ByteBuffer.allocate(65535)
    private val plaintext = ByteBuffer.allocate(65535)
    private val empty = ByteBuffer.allocate(0)

    fun receive(): AapMessage {
        while (true) {
            check()
            val frame = read()
            if (!frame.encrypted) throw ProjectionProtocolException("Clear application frame after AuthComplete")
            val source = ByteBuffer.wrap(frame.payload)
            val decoded = ByteArrayOutputStream()
            var rounds = 0
            while (source.hasRemaining()) {
                check()
                if (++rounds > 128) throw SSLException("TLS unwrap progress limit")
                plaintext.clear()
                val result = engine.unwrap(source, plaintext)
                if (result.status == Status.CLOSED) throw ProjectionTlsClosed()
                if (result.status != Status.OK) throw SSLException("Incomplete or oversized TLS application record: ${result.status}")
                requireApplicationState(result.handshakeStatus)
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) throw SSLException("TLS unwrap stalled")
                if (decoded.size() + plaintext.position() > 65535) throw SSLException("TLS plaintext envelope exceeds limit")
                decoded.write(plaintext.array(), 0, plaintext.position())
            }
            // TLS-only control records use complete envelopes. An empty continuation can
            // still finish a fragmented AAP message and must reach the assembler.
            if (decoded.size() == 0 && frame.first && frame.last) continue
            val message = assembler.accept(frame.copy(flags = frame.flags and AapFrame.ENCRYPTED.inv(),
                payload = decoded.toByteArray()))
            if (message != null) return message
        }
    }

    fun send(message: AapMessage) {
        require(message.body.size <= 32766) { "Control message exceeds plaintext limit" }
        val source = ByteBuffer.wrap(byteArrayOf((message.type ushr 8).toByte(), message.type.toByte()) + message.body)
        val encrypted = ByteArrayOutputStream()
        var rounds = 0
        while (source.hasRemaining()) {
            check()
            if (++rounds > 128) throw SSLException("TLS wrap progress limit")
            network.clear()
            val result = engine.wrap(source, network)
            if (result.status != Status.OK) throw SSLException("TLS application write failed: ${result.status}")
            requireApplicationState(result.handshakeStatus)
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) throw SSLException("TLS wrap stalled")
            if (encrypted.size() + network.position() > 65535) throw SSLException("TLS ciphertext envelope exceeds limit")
            encrypted.write(network.array(), 0, network.position())
        }
        val flags = AapFrame.COMPLETE or AapFrame.ENCRYPTED or if (message.control) AapFrame.CONTROL else 0
        write(AapFrame(message.channel, flags, encrypted.toByteArray()))
    }

    /** Bounded outbound close_notify; caller supplies a short teardown deadline. */
    fun closeOutbound() {
        engine.closeOutbound()
        repeat(8) {
            if (engine.isOutboundDone) return
            check()
            network.clear()
            val result = engine.wrap(empty, network)
            if (network.position() > 0) write(AapFrame(0, AapFrame.COMPLETE or AapFrame.ENCRYPTED,
                network.array().copyOf(network.position())))
            if (result.status == Status.CLOSED || engine.isOutboundDone) return
            if (result.bytesProduced() == 0) throw SSLException("TLS close stalled")
        }
        throw SSLException("TLS close progress limit")
    }

    private fun requireApplicationState(state: HandshakeStatus) {
        // TLS 1.2 renegotiation is deliberately unsupported until the duplex session pump supports it.
        if (state != HandshakeStatus.NOT_HANDSHAKING && state != HandshakeStatus.FINISHED) {
            throw SSLException("TLS renegotiation is not supported")
        }
    }
}
