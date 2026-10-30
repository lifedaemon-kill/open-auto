package com.github.lfdk.OAM.connection

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RadioStatus { ON, OFF, PERMISSION_REQUIRED, UNAVAILABLE }

data class PairedDevice(val address: String, val name: String)
data class BluetoothState(
    val status: RadioStatus = RadioStatus.UNAVAILABLE,
    val devices: List<PairedDevice> = emptyList(),
    val message: String? = null
)
data class VolumeState(val level: Int = 0, val maximum: Int = 0, val fixed: Boolean = true)
data class HeadUnitState(
    val bluetooth: BluetoothState = BluetoothState(),
    val wifi: RadioStatus = RadioStatus.UNAVAILABLE,
    val volume: VolumeState = VolumeState()
)

/** Platform diagnostics only. Pairing does not imply an audio or projection session. */
class HeadUnitRepository(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun read(): HeadUnitState = withContext(Dispatchers.IO) {
        HeadUnitState(readBluetooth(), readWifi(), readVolume())
    }

    private fun readBluetooth(): BluetoothState {
        return try {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return BluetoothState(message = "Стандартный Bluetooth-адаптер недоступен. Возможно, используется отдельный модуль производителя.")
            if (!hasBluetoothPermission()) return BluetoothState(RadioStatus.PERMISSION_REQUIRED)
            if (!adapter.isEnabled) return BluetoothState(RadioStatus.OFF)
            val devices = adapter.bondedDevices.orEmpty().map {
                PairedDevice(it.address, it.name?.takeIf(String::isNotBlank) ?: "Устройство без имени")
            }.sortedWith(compareBy<PairedDevice> { it.name }.thenBy { it.address })
            BluetoothState(RadioStatus.ON, devices)
        } catch (_: SecurityException) {
            BluetoothState(RadioStatus.PERMISSION_REQUIRED)
        } catch (_: IllegalStateException) {
            BluetoothState(message = "Bluetooth-сервис временно недоступен.")
        }
    }

    private fun readWifi(): RadioStatus = try {
        app.getSystemService(WifiManager::class.java)?.let {
            if (it.isWifiEnabled) RadioStatus.ON else RadioStatus.OFF
        } ?: RadioStatus.UNAVAILABLE
    } catch (_: SecurityException) {
        RadioStatus.UNAVAILABLE
    }

    fun readVolume(): VolumeState = try {
        audio?.let {
            VolumeState(it.getStreamVolume(AudioManager.STREAM_MUSIC),
                it.getStreamMaxVolume(AudioManager.STREAM_MUSIC), it.isVolumeFixed)
        } ?: VolumeState()
    } catch (_: SecurityException) {
        VolumeState()
    }

    /** This controls Android's media stream; a separate MCU amplifier may need an OEM adapter. */
    fun setVolume(level: Int): Result<VolumeState> = runCatching {
        val manager = checkNotNull(audio) { "Сервис громкости недоступен." }
        check(!manager.isVolumeFixed) { "Громкость регулируется средствами магнитолы." }
        manager.setStreamVolume(AudioManager.STREAM_MUSIC,
            level.coerceIn(0, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
        readVolume()
    }

    fun deviceReport(): String = """
        Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        ABI: ${Build.SUPPORTED_ABIS.joinToString()}
        Bluetooth-музыка: требуется A2DP sink / AVRCP прошивки
        Android Auto: протокольное ядро пока не подключено
        Подключение: только беспроводное, USB не используется
    """.trimIndent()
}
