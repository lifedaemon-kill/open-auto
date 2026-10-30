package com.github.lfdk.OAM.projection

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

enum class ProjectionCheckMode { TLS_PROBE, CONTROL_SESSION }

sealed interface WirelessProbeState {
    data object Idle : WirelessProbeState
    data object Preparing : WirelessProbeState
    data object Connecting : WirelessProbeState
    data object Negotiating : WirelessProbeState
    data object Securing : WirelessProbeState
    data object Stopping : WirelessProbeState
    data object Cancelled : WirelessProbeState
    data class ControlConnected(val connection: SecureProbeResult, val discovered: Boolean = false) : WirelessProbeState
    data class SessionEnded(val reason: ControlSessionEnd) : WirelessProbeState
    data class ReviewPeer(val host: String, val fingerprint: String, val changed: Boolean) : WirelessProbeState
    data class Reply(val result: SecureProbeResult) : WirelessProbeState
    data class Failed(val message: String) : WirelessProbeState
}

private sealed interface ConnectionResult {
    data class Probe(val result: SecureProbeResult) : ConnectionResult
    data class Session(val end: ControlSessionEnd) : ConnectionResult
}

class WirelessProbeViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    val host = saved.getStateFlow("projection_phone_ip", "")
    private val trust = application.getSharedPreferences("projection_peer_pins", Context.MODE_PRIVATE)
    private val receiverId = trust.getString("receiver_id", null) ?: UUID.randomUUID().toString().also {
        trust.edit().putString("receiver_id", it).apply()
    }
    private val _state = MutableStateFlow<WirelessProbeState>(WirelessProbeState.Idle)
    val state = _state.asStateFlow()
    private val generation = AtomicInteger()
    private var call: Closeable? = null
    private var session: ProjectionControlSession? = null
    private var job: Job? = null
    private var mode = ProjectionCheckMode.TLS_PROBE

    private val handshaking: Boolean get() = state.value in listOf(WirelessProbeState.Preparing,
        WirelessProbeState.Connecting, WirelessProbeState.Negotiating, WirelessProbeState.Securing)
    val running: Boolean get() = handshaking || state.value is WirelessProbeState.ControlConnected ||
        state.value == WirelessProbeState.Stopping

    fun chooseMode(value: ProjectionCheckMode) { if (!running) mode = value }

    fun editHost(value: String) {
        if (!running) {
            saved["projection_phone_ip"] = value.take(64)
            if (state.value is WirelessProbeState.ReviewPeer) _state.value = WirelessProbeState.Idle
        }
    }

    fun hasNetworkAccess(): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED

    fun validateHost(): Boolean = try {
        ProjectionEndpoint.parse(host.value)
        true
    } catch (failure: IllegalArgumentException) {
        _state.value = WirelessProbeState.Failed(failure.message ?: "Проверь адрес телефона.")
        false
    }

    fun acceptPeer() {
        val review = state.value as? WirelessProbeState.ReviewPeer ?: return
        if (host.value.trim() != review.host) return
        trust.edit().putString("peer_${review.host}", review.fingerprint).apply()
        _state.value = WirelessProbeState.Idle
        start()
    }

    fun start() {
        if (running || !validateHost()) return
        if (!hasNetworkAccess()) {
            _state.value = WirelessProbeState.Failed("Нет разрешения на доступ к локальной сети.")
            return
        }
        val endpoint = ProjectionEndpoint.parse(host.value)
        val peerHost = endpoint.address.address.hostAddress!!
        val pin = trust.getString("peer_$peerHost", null)
        val token = generation.incrementAndGet()
        val selectedMode = mode
        val control = if (selectedMode == ProjectionCheckMode.CONTROL_SESSION) ProjectionControlSession() else null
        val probe = if (control == null) ProjectionVersionProbe() else null
        val activeCall: Closeable = control ?: probe!!
        session = control
        call = activeCall
        _state.value = WirelessProbeState.Preparing
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    check(hasNetworkAccess()) { "Доступ к локальной сети выключен." }
                    val client = AndroidProjectionIdentity.client(pin)
                    val stages: (ProbeStage) -> Unit = { stage ->
                        viewModelScope.launch {
                            if (generation.get() == token && handshaking) _state.value = when (stage) {
                                ProbeStage.CONNECTING -> WirelessProbeState.Connecting
                                ProbeStage.NEGOTIATING -> WirelessProbeState.Negotiating
                                ProbeStage.SECURING -> WirelessProbeState.Securing
                            }
                        }
                    }
                    if (control == null) ConnectionResult.Probe(probe!!.executeTls(endpoint, client, stages))
                    else ConnectionResult.Session(control.execute(endpoint, client, ProjectionHeadUnit(receiverId), stages) { event ->
                        viewModelScope.launch {
                            if (generation.get() == token && running && _state.value != WirelessProbeState.Stopping) {
                                when (event) {
                                    is ControlSessionEvent.Authenticated ->
                                        _state.value = WirelessProbeState.ControlConnected(event.connection)
                                    ControlSessionEvent.CapabilitiesSent -> {
                                        val current = state.value as? WirelessProbeState.ControlConnected
                                        if (current != null) _state.value = current.copy(discovered = true)
                                    }
                                }
                            }
                        }
                    })
                }
                if (generation.get() == token) _state.value = when (result) {
                    is ConnectionResult.Probe -> WirelessProbeState.Reply(result.result)
                    is ConnectionResult.Session -> WirelessProbeState.SessionEnded(result.end)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (review: ProjectionPeerReviewRequired) {
                if (generation.get() == token) _state.value = WirelessProbeState.ReviewPeer(peerHost, review.fingerprint, review.changed)
            } catch (failure: Exception) {
                if (generation.get() == token) {
                    val message = when (failure) {
                        is SocketTimeoutException -> "Телефон не ответил вовремя. Проверь Wi-Fi и сервер Android Auto."
                        is ConnectException -> "Телефон не принимает соединение. Проверь сервер Android Auto."
                        is EOFException -> "Телефон закрыл соединение."
                        is ProjectionVersionRejected -> "Телефон отклонил версию протокола."
                        is SSLException -> "Согласование TLS или защищённый обмен завершились ошибкой."
                        is GeneralSecurityException -> "Не удалось подготовить ключ приёмника. Проверь дату и Android Keystore."
                        is ProjectionProtocolException -> "Получен неожиданный или неподдерживаемый ответ Android Auto."
                        is SecurityException -> "Нет доступа к локальной сети. Проверь разрешения приложения."
                        is IOException -> "Соединение прервано. Проверь Wi-Fi на обоих устройствах."
                        else -> "Не удалось открыть защищённое соединение."
                    }
                    _state.value = WirelessProbeState.Failed(message)
                }
            } finally {
                runCatching { activeCall.close() }
                if (generation.get() == token) { call = null; session = null }
            }
        }
    }

    fun cancel() {
        val active = session
        if (active != null && running) {
            _state.value = WirelessProbeState.Stopping
            active.requestStop()
            return
        }
        abort()
    }

    private fun abort() {
        generation.incrementAndGet()
        runCatching { call?.close() }
        job?.cancel()
        call = null
        session = null
        job = null
        if (running || state.value is WirelessProbeState.ReviewPeer) _state.value = WirelessProbeState.Cancelled
    }

    override fun onCleared() {
        abort()
        super.onCleared()
    }
}
