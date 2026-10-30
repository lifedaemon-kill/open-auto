package com.github.lfdk.OAM.projection

import java.io.Closeable
import java.io.IOException
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class ProjectionStopRequested : IOException("Projection stop requested")
enum class ControlSessionEnd { USER_STOPPED, PEER_ENDED }
sealed interface ControlSessionEvent {
    data class Authenticated(val connection: SecureProbeResult) : ControlSessionEvent
    data object CapabilitiesSent : ControlSessionEvent
}

/** Each session has one TLS/IO owner. Media callbacks and UI input only enqueue messages. */
class ProjectionControlSession(private val mediaSink: ProjectionMediaSink? = null,
    acceptedChannel: SocketChannel? = null) : Closeable {
    private val stopped = AtomicBoolean()
    private val probe = ProjectionVersionProbe(acceptedChannel = acceptedChannel)
    private val outgoing = ArrayBlockingQueue<ProjectionOutbound>(128)
    @Volatile private var media: ProjectionMediaProtocol? = null
    @Volatile private var outputFailure: Exception? = null

    init { probe.transport.stopSignal = stopped::get }

    private fun enqueue(message: AapMessage) {
        enqueueGuarded(ProjectionOutbound(message))
    }

    private fun enqueueGuarded(message: ProjectionOutbound) {
        if (!outgoing.offer(message)) { fail(IOException("Projection output queue is full")); return }
        probe.transport.wakeup()
    }

    fun touch(event: ProjectionTouch): Boolean {
        if (media?.inputReady != true || stopped.get()) return false
        enqueue(event.message())
        return true
    }

    fun focusChanged(state: Int) {
        if (mediaSink != null) enqueue(AapMessage(0, false, 19, wire { writeEnum(1, state); writeBool(2, true) }))
    }

    fun fail(failure: Exception) {
        outputFailure = failure
        close()
    }

    fun execute(endpoint: ProjectionEndpoint, client: ProjectionTlsClient, headUnit: ProjectionHeadUnit,
        onStage: (ProbeStage) -> Unit = {}, onEvent: (ControlSessionEvent) -> Unit = {}): ControlSessionEnd {
        try {
            return probe.withTls(endpoint, client, onStage) { connection, transport ->
                val tls = AapTlsChannel(client.engine, transport::readFrame, transport::writeFrame, transport::checkDeadline)
                val microphone = mediaSink?.microphone?.let { ProjectionMicrophoneProtocol(it, ::enqueueGuarded) }
                val mediaProtocol = mediaSink?.let { ProjectionMediaProtocol(it, microphone, ::enqueue) }
                media = mediaProtocol
                val control = AapControlProtocol(headUnit, mediaProtocol, mediaSink)
                fun drain() { transport.withWriteDeadline {
                    repeat(128) { val item = outgoing.poll() ?: return@withWriteDeadline
                        if (item.claim()) tls.send(item.message)
                    }
                } }
                transport.onReadWait = ::drain
                onEvent(ControlSessionEvent.Authenticated(connection))
                try {
                    while (true) {
                        transport.beginPhase(10000)
                        drain()
                        val message = tls.receive()
                        transport.beginPhase(3000)
                        if (message.channel != 0 && !message.control) {
                            val handler = mediaProtocol ?: throw ProjectionProtocolException("No media sink attached")
                            handler.handle(message).forEach(tls::send)
                        } else {
                            val action = control.handle(message)
                            action.response?.let(tls::send)
                            if (action.discovered) onEvent(ControlSessionEvent.CapabilitiesSent)
                            if (action.end) break
                        }
                    }
                    ControlSessionEnd.PEER_ENDED
                } catch (_: ProjectionTlsClosed) {
                    ControlSessionEnd.PEER_ENDED
                } catch (_: ProjectionStopRequested) {
                    transport.onReadWait = {}
                    transport.stopSignal = { false }
                    transport.beginPhase(1000)
                    runCatching { tls.send(control.byeBye()) }
                    ControlSessionEnd.USER_STOPPED
                } finally {
                    microphone?.close()
                    transport.onReadWait = {}
                    transport.stopSignal = { false }
                    transport.beginPhase(1000)
                    runCatching { tls.closeOutbound() }
                }
            }
        } catch (_: ProjectionStopRequested) {
            return ControlSessionEnd.USER_STOPPED
        } catch (failure: Exception) {
            outputFailure?.let { throw IOException("Media output failed", it) }
            throw failure
        } finally {
            media = null
            outgoing.clear()
            mediaSink?.close()
        }
    }

    fun requestStop() { stopped.set(true); probe.transport.wakeup() }
    override fun close() = probe.close()
}
