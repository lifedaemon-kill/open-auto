package com.github.lfdk.OAM.projection

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

data class ProjectionTlsResult(val protocol: String, val cipherSuite: String)

/** TLS records are inside clear AAP type-3 messages, rather than directly on the TCP socket. */
internal class AapTlsHandshake(private val engine: SSLEngine) {
    fun execute(read: () -> AapFrame, write: (AapFrame) -> Unit, check: () -> Unit): ProjectionTlsResult {
        val assembler = AapMessageAssembler(MAX_BUFFER)
        val pending = ByteBuffer.allocate(MAX_BUFFER)
        var application = ByteBuffer.allocate(engine.session.applicationBufferSize.coerceAtLeast(16384))
        var network = ByteBuffer.allocate(engine.session.packetBufferSize.coerceAtLeast(32768))
        if (application.capacity() > MAX_BUFFER || network.capacity() > MAX_BUFFER) throw SSLException("TLS provider buffers exceed limit")
        var needMore = true
        var received = 0
        var frames = 0
        var rounds = 0
        var finished = false

        fun receiveRecord() {
            while (true) {
                check()
                if (++frames > 512) throw SSLException("Too many TLS handshake envelopes")
                val frame = read()
                if (frame.channel != 0 || frame.encrypted) throw SSLException("Unexpected TLS handshake envelope")
                val message = assembler.accept(frame) ?: continue
                if (message.type == 2) {
                    if (!NegotiatedVersion.from(message).accepted) throw SSLException("Peer rejected version")
                    continue
                }
                if (message.type != 3 || message.body.isEmpty()) throw SSLException("Expected encapsulated TLS records")
                received += message.body.size
                if (received > 2 * 1024 * 1024 || message.body.size > pending.remaining()) {
                    throw SSLException("TLS handshake input exceeds limit")
                }
                pending.put(message.body)
                return
            }
        }
        fun sendRecords(bytes: ByteArray) {
            // TLS records can span multiple AAP messages; preserve the byte stream.
            for (chunk in bytes.asList().chunked(65533)) {
                write(AapFrame(0, AapFrame.COMPLETE, byteArrayOf(0, 3) + chunk.toByteArray()))
            }
        }
        try {
            engine.beginHandshake()
            while (!finished) {
                check()
                if (++rounds > 2048) throw SSLException("TLS handshake made insufficient progress")
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_TASK -> {
                        var tasks = 0
                        while (engine.handshakeStatus == HandshakeStatus.NEED_TASK) {
                            check()
                            if (++tasks > 64) throw SSLException("TLS delegated task limit exceeded")
                            (engine.delegatedTask ?: throw SSLException("Missing TLS task")).run()
                        }
                    }
                    HandshakeStatus.NEED_WRAP -> {
                        network.clear()
                        val previous = engine.handshakeStatus
                        val result = engine.wrap(ByteBuffer.allocate(0), network)
                        if (result.status == Status.BUFFER_OVERFLOW) {
                            if (network.capacity() >= MAX_BUFFER) throw SSLException("TLS output buffer exhausted")
                            network = ByteBuffer.allocate((network.capacity() * 2).coerceAtMost(MAX_BUFFER))
                            continue
                        }
                        if (result.status != Status.OK) throw SSLException("TLS handshake wrap failed: ${result.status}")
                        if (network.position() > 0) sendRecords(network.array().copyOf(network.position()))
                        if (result.bytesProduced() == 0 && previous == engine.handshakeStatus) throw SSLException("TLS wrap stalled")
                        if (result.handshakeStatus == HandshakeStatus.FINISHED) finished = true
                    }
                    HandshakeStatus.NEED_UNWRAP -> {
                        if (needMore || pending.position() == 0) receiveRecord()
                        pending.flip()
                        application.clear()
                        val previous = engine.handshakeStatus
                        val result = try { engine.unwrap(pending, application) } finally { pending.compact() }
                        if (result.bytesProduced() > 0) throw SSLException("Unexpected application data during TLS handshake")
                        when (result.status) {
                            Status.BUFFER_UNDERFLOW -> needMore = true
                            Status.BUFFER_OVERFLOW -> {
                                if (application.capacity() >= MAX_BUFFER) throw SSLException("TLS plaintext buffer exhausted")
                                application = ByteBuffer.allocate((application.capacity() * 2).coerceAtMost(MAX_BUFFER))
                                needMore = false
                            }
                            Status.OK -> {
                                needMore = false
                                if (result.bytesConsumed() == 0 && previous == engine.handshakeStatus) throw SSLException("TLS unwrap stalled")
                            }
                            else -> throw SSLException("TLS handshake closed by peer")
                        }
                        if (result.handshakeStatus == HandshakeStatus.FINISHED) finished = true
                    }
                    HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> finished = true
                    else -> throw SSLException("Unsupported TLS handshake state")
                }
            }
            check()
            if (pending.position() != 0) throw SSLException("Unconsumed records after TLS handshake")
            return ProjectionTlsResult(engine.session.protocol, engine.session.cipherSuite)
        } catch (failure: Exception) {
            engine.closeOutbound()
            throw failure
        }
    }

    companion object { private const val MAX_BUFFER = 256 * 1024 }
}
