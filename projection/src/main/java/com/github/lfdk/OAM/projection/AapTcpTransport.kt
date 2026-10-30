package com.github.lfdk.OAM.projection

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One IO owner. Cancellation may close it from another thread; reads AND writes share a deadline. */
internal class AapTcpTransport(acceptedChannel: SocketChannel? = null) : Closeable {
    private val channel = (acceptedChannel ?: SocketChannel.open()).apply { configureBlocking(false) }
    private val selector = Selector.open()
    private val key = channel.register(selector, 0)
    private val closed = AtomicBoolean()
    private var deadline = 0L
    @Volatile var stopSignal: () -> Boolean = { false }
    var onReadWait: () -> Unit = {}

    fun wakeup() { runCatching { selector.wakeup() } }

    fun <T> withWriteDeadline(block: () -> T): T {
        val previous = deadline
        deadline = minOf(previous, System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
        try { return block() } finally { deadline = previous }
    }

    fun beginPhase(timeoutMs: Int) {
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
    }

    fun checkDeadline() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Projection call cancelled")
        if (stopSignal()) throw ProjectionStopRequested()
        if (System.nanoTime() >= deadline) throw SocketTimeoutException("Projection phase timed out")
    }

    fun connect(endpoint: ProjectionEndpoint) {
        checkDeadline()
        channel.socket().tcpNoDelay = true
        if (channel.isConnected) return
        if (!channel.connect(endpoint.address)) {
            while (!channel.finishConnect()) await(SelectionKey.OP_CONNECT)
        }
    }

    private fun await(operation: Int) {
        try {
            while (true) {
                checkDeadline()
                if (operation == SelectionKey.OP_READ) onReadWait()
                key.interestOps(operation)
                val millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                selector.select(millis)
                checkDeadline()
                val ready = selector.selectedKeys().any { it.isValid && it.readyOps() and operation != 0 }
                selector.selectedKeys().clear()
                if (ready) return
            }
        } catch (_: ClosedSelectorException) {
            throw InterruptedIOException("Projection call cancelled")
        } catch (_: CancelledKeyException) {
            throw InterruptedIOException("Projection call cancelled")
        }
    }

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val target = ByteBuffer.wrap(bytes, offset, length)
            while (true) {
                checkDeadline()
                val read = channel.read(target)
                if (read != 0) return read
                await(SelectionKey.OP_READ)
            }
        }
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val source = ByteBuffer.wrap(bytes, offset, length)
            while (source.hasRemaining()) {
                checkDeadline()
                if (channel.write(source) == 0) await(SelectionKey.OP_WRITE)
            }
        }
    }

    fun readFrame(): AapFrame = AapFrameCodec.read(input, ::checkDeadline)
    fun writeFrame(frame: AapFrame) {
        checkDeadline()
        AapFrameCodec.write(output, frame)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { channel.close() } finally { selector.wakeup(); selector.close() }
        }
    }
}
