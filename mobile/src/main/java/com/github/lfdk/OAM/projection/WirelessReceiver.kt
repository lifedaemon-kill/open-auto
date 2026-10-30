package com.github.lfdk.OAM.projection

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Standard Android radio path. The OEM hands-free profile remains responsible for call audio. */
internal class WirelessReceiver(
    private val context: Context,
    parent: CoroutineScope,
    private val status: (String) -> Unit,
    private val connected: (SocketChannel, String) -> Boolean
) : Closeable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val closed = AtomicBoolean()
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var ownsGroup = false
    @Volatile private var creatingGroup = false
    private val projecting = AtomicBoolean()
    @Volatile private var watchdog: Job? = null
    val isClosed: Boolean get() = closed.get()
    fun sessionEnded() { projecting.set(false) }
    @Volatile private var listener: ServerSocketChannel? = null
    @Volatile private var bluetooth: BluetoothServerSocket? = null
    @Volatile private var phone: BluetoothSocket? = null
    @Volatile private var phoneIdentity: String? = null
    @Volatile private var protocol: WirelessBootstrapProtocol? = null
    private val writes = Any()
    private var starter: Job? = null

    fun start() {
        if (closed.get() || starter != null) return
        starter = scope.launch {
            try {
                permissions()
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IOException("Стандартный Bluetooth недоступен. Нужен адаптер прошивки магнитолы.")
                if (!adapter.isEnabled) throw IOException("Включи Bluetooth в настройках магнитолы.")
                val manager = context.getSystemService(WifiP2pManager::class.java)
                    ?: throw IOException("Магнитола не поддерживает Wi-Fi Direct.")
                p2p = manager
                val channel = manager.initialize(context, Looper.getMainLooper()) {
                    if (!closed.get()) { status("Связь с Wi-Fi Direct потеряна. Подключись повторно."); close() }
                }
                p2pChannel = channel
                if (group(manager, channel) != null) throw IOException("Wi-Fi Direct уже занят. Заверши другое подключение и повтори.")
                status("Создаём сеть Android Auto…")
                withTimeout(15000) {
                    val name = "DIRECT-OA-" + UUID.randomUUID().toString().take(6)
                    val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    val config = if (Build.VERSION.SDK_INT >= 29) WifiP2pConfig.Builder()
                        .setNetworkName(name).setPassphrase(secret)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ).build() else null
                    try { createGroup(manager, channel, config) }
                    catch (_: IOException) { createGroup(manager, channel, null) }
                    ownsGroup = true
                    var actual: WifiP2pGroup? = null
                    var info: WifiP2pInfo? = null
                    while (actual?.passphrase.isNullOrBlank() || info?.groupFormed != true || info.isGroupOwner != true) {
                        ensureActive()
                        actual = group(manager, channel); info = connection(manager, channel)
                        if (actual?.passphrase.isNullOrBlank() || !info.groupFormed || !info.isGroupOwner) delay(200)
                    }
                    val address = info.groupOwnerAddress?.hostAddress ?: throw IOException("Wi-Fi Direct не выдал IP-адрес.")
                    val mac = actual.owner?.deviceAddress.orEmpty().takeUnless { it == "02:00:00:00:00:00" }.orEmpty()
                    val network = WirelessNetwork(actual.networkName, actual.passphrase, mac, address)
                    protocol = WirelessBootstrapProtocol(network)
                    withContext(Dispatchers.IO) {
                        permissions()
                        val server = ServerSocketChannel.open()
                        listener = server
                        server.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
                        server.bind(InetSocketAddress(address, 5288))
                        server.configureBlocking(false)
                        try {
                            bluetooth = adapter.listenUsingRfcommWithServiceRecord("OpenAuto Android Auto",
                                UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"))
                        } catch (denied: SecurityException) { throw denied }
                    }
                }
                status("Готово к подключению. На телефоне выбери магнитолу в настройках Android Auto.")
                launch(Dispatchers.IO) { acceptProjection() }
                launch(Dispatchers.IO) { acceptBluetooth() }
            } catch (cancelled: CancellationException) {
                close(); throw cancelled
            } catch (failure: Exception) {
                if (!closed.get()) status(failure.message ?: "Не удалось запустить беспроводной приёмник.")
                close()
            }
        }
    }

    private fun permissions() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) required += Manifest.permission.BLUETOOTH_CONNECT
        required += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 37) required += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (required.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            throw SecurityException("Разреши доступ к устройствам поблизости и локальной сети.")
        }
    }

    private fun acceptBluetooth() {
        try {
            while (!closed.get()) {
                permissions()
                val socket = bluetooth?.accept() ?: break
                phone = socket
                try {
                    permissions()
                    if (socket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) continue
                    phoneIdentity = socket.remoteDevice.address
                    status("Телефон подключился. Передаём параметры Wi-Fi…")
                    watchdog = scope.launch { delay(60000); if (!projecting.get()) runCatching { socket.close() } }
                    send(protocol!!.start())
                    try {
                        while (!closed.get()) {
                            val incoming = WirelessMessageCodec.read(socket.inputStream)
                            protocol!!.respond(incoming)?.let(::send)
                        }
                    } finally { watchdog?.cancel() }
                } catch (_: SecurityException) {
                    if (!closed.get()) status("Доступ к Bluetooth отозван. Разреши его в настройках OpenAuto.")
                    close()
                } catch (_: Exception) {
                    if (!closed.get() && !projecting.get()) status("Bluetooth-настройка завершена или прервана. Ожидаем подключение телефона.")
                } finally { phone = null; runCatching { socket.close() } }
            }
        } catch (failure: Exception) {
            if (!closed.get()) { status("Bluetooth недоступен: ${failure.message}"); close() }
        }
    }

    private fun send(message: WirelessMessage) = synchronized(writes) {
        val socket = phone ?: return@synchronized
        WirelessMessageCodec.write(socket.outputStream, message)
    }

    fun reconnect() {
        scope.launch(Dispatchers.IO) { try {
            permissions()
            protocol?.start()?.let(::send)
        } catch (_: Exception) {} }
    }

    private fun acceptProjection() {
        try {
            Selector.open().use { selector ->
                val server = listener ?: return
                server.register(selector, SelectionKey.OP_ACCEPT)
                while (!closed.get()) {
                    selector.select(1000)
                    selector.selectedKeys().clear()
                    val socket = server.accept() ?: continue
                    var transferred = false
                    try {
                        val identity = phoneIdentity ?: continue
                        permissions()
                        socket.configureBlocking(false)
                        transferred = connected(socket, "bt:$identity")
                        if (transferred) { projecting.set(true); watchdog?.cancel() }
                    } finally { if (!transferred) socket.close() }
                }
            }
        } catch (failure: Exception) {
            if (!closed.get()) { status("Wi-Fi соединение прервано: ${failure.message}"); close() }
        }
    }

    private suspend fun group(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { result ->
            try {
                permissions()
                manager.requestGroupInfo(channel) { if (result.isActive) result.resume(it) }
            } catch (denied: SecurityException) {
                if (result.isActive) result.resumeWithException(denied)
            }
        }

    private suspend fun connection(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pInfo =
        suspendCancellableCoroutine { result ->
            try {
                permissions()
                manager.requestConnectionInfo(channel) { if (result.isActive) result.resume(it) }
            } catch (denied: SecurityException) {
                if (result.isActive) result.resumeWithException(denied)
            }
        }

    private suspend fun createGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig?) {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("Receiver closed")
        suspendCancellableCoroutine<Unit> { result ->
            permissions()
            creatingGroup = true
            val callback = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    creatingGroup = false
                    ownsGroup = true
                    if (closed.get()) releaseGroup(manager, channel)
                    if (result.isActive) result.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    creatingGroup = false
                    if (closed.get()) channel.close()
                    if (result.isActive) result.resumeWithException(IOException("Wi-Fi Direct: ошибка $reason"))
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 29 && config != null) manager.createGroup(channel, config, callback)
                else manager.createGroup(channel, callback)
            } catch (denied: SecurityException) {
                creatingGroup = false
                if (result.isActive) result.resumeWithException(denied)
            }
        }
    }

    private fun releaseGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { channel.close() }
                override fun onFailure(reason: Int) { channel.close() }
            })
        } catch (_: Exception) { channel.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        runCatching { phone?.close() }; runCatching { bluetooth?.close() }; runCatching { listener?.close() }
        protocol = null
        val manager = p2p; val channel = p2pChannel
        if (channel != null) {
            if (ownsGroup && manager != null) releaseGroup(manager, channel) else if (!creatingGroup) channel.close()
        }
    }
}
