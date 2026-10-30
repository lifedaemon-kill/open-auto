package com.github.lfdk.OAM.projection

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Capture starts only on the phone's explicit Open. Implementations never block the IO owner. */
interface ProjectionMicrophoneSource : Closeable {
    fun start(ready: (Boolean) -> Unit, data: (ByteArray, Long) -> Unit, failed: () -> Unit)
    fun stop()
}

internal data class ProjectionOutbound(val message: AapMessage, val claim: () -> Boolean = { true })

/** Each Open has its own wire id and credit window. Old capture callbacks/queued DATA are retired. */
internal class ProjectionMicrophoneProtocol(
    private val source: ProjectionMicrophoneSource,
    private val enqueue: (ProjectionOutbound) -> Unit
) : Closeable {
    private data class Chunk(val bytes: ByteArray, val stamp: Long)
    private class Run(val id: Int, val window: Int) {
        var ready = false
        var reserved = 0
        var sent = 0
        val pending = ArrayDeque<Chunk>()
    }
    private var sequence = 0
    private var current: Run? = null
    private var closed = false

    @Synchronized fun handle(message: AapMessage) {
        when (message.type) {
            0x8000 -> {
                if (numeric(message.body, 1) != 1L) throw ProjectionProtocolException("Unsupported microphone codec")
                enqueue(ProjectionOutbound(AapMessage(6, false, 0x8003,
                    wire { writeEnum(1, 2); writeUInt32(2, 4); writeUInt32(3, 0) })))
            }
            0x8001 -> {
                if (numeric(message.body, 2) != 0L) throw ProjectionProtocolException("Invalid microphone configuration")
            }
            0x8002 -> retire()
            0x8005 -> {
                val open = numeric(message.body, 1)
                if (open !in 0..1) throw ProjectionProtocolException("Invalid microphone request")
                if (open == 0L) { retire(); response(sequence, true); return }
                val window = numeric(message.body, 4, required = false, defaultValue = 2)
                if (window !in 1..4096) { retire(); response(sequence, false); return }
                current?.let { if (it.ready) response(it.id, true); return }
                if (closed) return
                val run = Run(++sequence, minOf(window.toInt(), 16))
                current = run
                source.start(
                    ready = { success -> captureReady(run, success) },
                    data = { bytes, stamp -> captured(run, bytes, stamp) },
                    failed = { captureFailed(run) })
            }
            0x8004 -> {
                val id = numeric(message.body, 1)
                val count = numeric(message.body, 2)
                val run = current ?: return
                // Delayed ACKs for a retired session cannot grant credits to its replacement.
                if (id != run.id.toLong()) return
                if (count <= 0 || count > run.sent) throw ProjectionProtocolException("Invalid microphone ACK")
                run.sent -= count.toInt(); run.reserved -= count.toInt()
                pump(run)
            }
            else -> throw ProjectionProtocolException("Unsupported microphone message")
        }
    }

    @Synchronized private fun captureReady(run: Run, success: Boolean) {
        if (current !== run || closed) return
        response(run.id, success) // Always queued before the first DATA.
        if (!success) retire() else { run.ready = true; pump(run) }
    }

    @Synchronized private fun captured(run: Run, bytes: ByteArray, stamp: Long) {
        if (current !== run || closed) return
        if (bytes.isEmpty() || bytes.size > 8192 || bytes.size % 2 != 0 || stamp < 0 || run.pending.size >= 4) {
            captureFailed(run); return
        }
        run.pending.addLast(Chunk(bytes.copyOf(), stamp))
        pump(run)
    }

    private fun pump(run: Run) {
        while (current === run && run.ready && run.reserved < run.window && run.pending.isNotEmpty()) {
            val chunk = run.pending.removeFirst()
            run.reserved++
            enqueue(ProjectionOutbound(AapMessage(6, false, 0,
                ByteBuffer.allocate(8 + chunk.bytes.size).putLong(chunk.stamp).put(chunk.bytes).array())) {
                synchronized(this) {
                    if (current !== run || closed || !run.ready) false else { run.sent++; true }
                }
            })
        }
    }

    @Synchronized private fun captureFailed(run: Run) {
        if (current !== run || closed) return
        retire(); response(run.id, false)
    }

    private fun response(id: Int, success: Boolean) {
        enqueue(ProjectionOutbound(AapMessage(6, false, 0x8006,
            wire { writeInt32(1, if (success) 0 else -1); writeInt32(2, id) })))
    }

    private fun retire() { current = null; source.stop() }
    @Synchronized override fun close() { closed = true; retire() }
}
