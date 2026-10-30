package com.github.lfdk.OAM.projection

import android.app.Application
import android.content.Context
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PlayerStatus(val message: String = "Ожидаем экран…", val peer: ProjectionPeerReviewRequired? = null)

/** Owns radios, network and media resources. No Activity/View survives a configuration change. */
class ProjectionPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("projection_peer_pins", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(PlayerStatus())
    val status = _status.asStateFlow()
    private var host = ""
    private var automatic = false
    @Volatile private var surface: Surface? = null
    @Volatile private var wanted = false
    @Volatile private var generation = 0
    private var job: Job? = null
    private var receiver: WirelessReceiver? = null
    private var reviewKey: String? = null
    private val occupied = AtomicBoolean()
    private val socket = AtomicReference<SocketChannel?>()
    private val active = AtomicReference<ProjectionControlSession?>()

    fun configure(host: String, automatic: Boolean = false) { this.host = host; this.automatic = automatic }

    fun connect() {
        wanted = true
        if (surface == null) return
        if (automatic) arm() else start(null, "peer_${host.trim()}")
    }

    fun restart() { abort(); connect() }

    private fun arm() {
        if (receiver?.isClosed == true) receiver = null
        if (receiver != null) { receiver?.reconnect(); return }
        receiver = WirelessReceiver(getApplication(), viewModelScope,
            status = { message -> viewModelScope.launch {
                if (wanted && !occupied.get() && status.value.peer == null) _status.value = PlayerStatus(message)
            } },
            connected = { incoming, key ->
                if (!wanted || surface == null || status.value.peer != null || !occupied.compareAndSet(false, true)) false
                else {
                    socket.set(incoming)
                    viewModelScope.launch {
                        if (wanted && surface != null && socket.get() === incoming) start(incoming, key)
                        else { socket.compareAndSet(incoming, null); incoming.close(); occupied.set(false) }
                    }
                    true
                }
            }).also { it.start() }
    }

    fun bind(value: Surface) { surface = value; if (wanted) connect() }
    fun detach() {
        surface = null; abort()
        if (wanted) _status.value = PlayerStatus("Ожидаем экран…")
    }

    fun stop() {
        wanted = false; abort()
        receiver?.close(); receiver = null
        reviewKey = null
        _status.value = PlayerStatus("Отключено")
    }

    fun trustPeer() {
        val peer = status.value.peer ?: return
        val key = reviewKey ?: return
        preferences.edit().putString(key, peer.fingerprint).apply()
        reviewKey = null
        _status.value = PlayerStatus("Сертификат сохранён. Ожидаем телефон…")
        connect()
    }

    fun touch(event: ProjectionTouch): Boolean = active.get()?.touch(event) == true

    private fun abort(releaseSlot: Boolean = true) {
        generation++
        active.getAndSet(null)?.close()
        socket.getAndSet(null)?.let { runCatching { it.close() } }
        if (releaseSlot) occupied.set(false)
        receiver?.sessionEnded()
        job?.cancel()
    }

    private fun start(incoming: SocketChannel?, pinKey: String) {
        val target = surface ?: run { incoming?.close(); occupied.set(false); return }
        val endpoint = try {
            if (incoming != null) {
                val remote = incoming.remoteAddress as InetSocketAddress
                ProjectionEndpoint.parse(remote.address.hostAddress!!)
            } else ProjectionEndpoint.parse(host)
        } catch (failure: Exception) {
            incoming?.close(); occupied.set(false)
            _status.value = PlayerStatus(failure.message ?: "Проверь адрес телефона")
            return
        }
        val previous = job
        // Preserve the accepted socket while retiring a previous decoder/session.
        socket.compareAndSet(incoming, null)
        abort(releaseSlot = false)
        socket.set(incoming)
        occupied.set(true)
        val token = generation
        reviewKey = null
        _status.value = PlayerStatus("Подключаем телефон…")
        job = viewModelScope.launch {
            try {
                previous?.join()
                withContext(Dispatchers.IO) {
                    ensureActive()
                    if (!target.isValid) return@withContext
                    val rendered = AtomicBoolean()
                    val sessionRef = AtomicReference<ProjectionControlSession?>()
                    val outputError = AtomicReference<Exception?>()
                    val outputs = ProjectionOutputs(getApplication(), target,
                        rendered = {
                            rendered.set(true)
                            viewModelScope.launch { if (token == generation) _status.value = PlayerStatus("Android Auto") }
                        },
                        failed = { outputError.set(it); sessionRef.get()?.fail(it) },
                        focusChanged = { sessionRef.get()?.focusChanged(it) })
                    val session = ProjectionControlSession(outputs, incoming)
                    sessionRef.set(session)
                    try {
                        outputError.get()?.let { throw it }
                        if (token != generation) return@withContext
                        active.set(session)
                        ensureActive()
                        if (token != generation) return@withContext
                        val client = AndroidProjectionIdentity.client(preferences.getString(pinKey, null))
                        val id = preferences.getString("receiver_id", null) ?: UUID.randomUUID().toString().also {
                            preferences.edit().putString("receiver_id", it).apply()
                        }
                        session.execute(endpoint, client, ProjectionHeadUnit(id), onEvent = {
                            if (!rendered.get()) viewModelScope.launch {
                                if (token == generation && !rendered.get())
                                    _status.value = PlayerStatus("Подключено. Ожидаем изображение…")
                            }
                        })
                    } finally {
                        session.close(); outputs.close(); active.compareAndSet(session, null)
                    }
                }
                if (token == generation) _status.value = PlayerStatus("Телефон завершил соединение. Нажми «Подключить» для повтора.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (peer: ProjectionPeerReviewRequired) {
                if (token == generation) {
                    reviewKey = pinKey
                    _status.value = PlayerStatus("Подтверди сертификат телефона", peer)
                }
            } catch (failure: Exception) {
                if (token == generation) _status.value = PlayerStatus("Ошибка подключения: ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                incoming?.let { runCatching { it.close() }; socket.compareAndSet(it, null) }
                if (token == generation) { occupied.set(false); receiver?.sessionEnded() }
            }
        }
    }

    override fun onCleared() { wanted = false; abort(); receiver?.close(); super.onCleared() }
}
