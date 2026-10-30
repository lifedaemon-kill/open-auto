package com.github.lfdk.OAM.projection

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.github.lfdk.OAM.connection.HeadUnitColors

/** Private foreground projection screen with independent, optional microphone permission. */
class ProjectionActivity : ComponentActivity() {
    private enum class Action { CONNECT, MICROPHONE, DISCOVERABLE }
    private lateinit var player: ProjectionPlayerViewModel
    private var automatic = false
    private var pending = Action.CONNECT
    private var rationale by mutableStateOf<Action?>(null)
    private var settingsNeeded by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val denied = missing(pending)
        if (denied.isEmpty()) perform(pending)
        else if (denied.any { !shouldShowRequestPermissionRationale(it) }) settingsNeeded = true
        else notice = "Разрешение не предоставлено. Подключение можно повторить кнопкой на экране."
    }

    private fun missing(action: Action): Array<String> {
        val required = mutableListOf<String>()
        when (action) {
            Action.CONNECT -> {
                if (Build.VERSION.SDK_INT >= 37) required += Manifest.permission.ACCESS_LOCAL_NETWORK
                if (automatic) {
                    if (Build.VERSION.SDK_INT >= 31) required += Manifest.permission.BLUETOOTH_CONNECT
                    if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.NEARBY_WIFI_DEVICES
                    else required += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
            Action.MICROPHONE -> required += Manifest.permission.RECORD_AUDIO
            Action.DISCOVERABLE -> if (Build.VERSION.SDK_INT >= 31) {
                required += Manifest.permission.BLUETOOTH_CONNECT
                required += Manifest.permission.BLUETOOTH_ADVERTISE
            }
        }
        return required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun request(action: Action) {
        pending = action
        val missing = missing(action)
        when {
            missing.isEmpty() -> perform(action)
            missing.any(::shouldShowRequestPermissionRationale) -> rationale = action
            else -> permissions.launch(missing)
        }
    }

    private fun perform(action: Action) {
        try {
            when (action) {
                Action.CONNECT -> player.restart()
                Action.MICROPHONE -> { player.restart(); notice = "Голосовой ввод разрешён для нового соединения." }
                Action.DISCOVERABLE -> discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }
        } catch (_: Exception) { notice = "Действие недоступно в этой прошивке. Проверь системные настройки Bluetooth." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        automatic = intent.getBooleanExtra("wireless", false)
        player = ViewModelProvider(this)[ProjectionPlayerViewModel::class.java]
        player.configure(intent.getStringExtra("phone_ip").orEmpty(), automatic)
        setContent {
            val status by player.status.collectAsState()
            MaterialTheme(colorScheme = HeadUnitColors) {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().background(Color.Black).padding(insets)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(status.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            TextButton(onClick = { adjustVolume(false) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("−") }
                            TextButton(onClick = { adjustVolume(true) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("+") }
                            TextButton(onClick = { request(Action.CONNECT) }) { Text("Подключить") }
                            TextButton(onClick = { player.stop(); finish() }) { Text("Выйти") }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            val width = minOf(maxWidth, maxHeight * (800f / 480f))
                            val height = width * (480f / 800f)
                            AndroidView(factory = { context ->
                                SurfaceView(context).apply {
                                    holder.setFixedSize(800, 480)
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) { player.bind(holder.surface) }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                        override fun surfaceDestroyed(holder: SurfaceHolder) { player.detach() }
                                    })
                                    var accepted = false
                                    setOnTouchListener { view, event ->
                                        if (event.actionMasked !in 0..6 || event.pointerCount !in 1..10) return@setOnTouchListener true
                                        val pointers = (0 until event.pointerCount).map {
                                            ProjectionPointer(event.getPointerId(it),
                                                (event.getX(it) * 800 / view.width.coerceAtLeast(1)).toInt().coerceIn(0, 799),
                                                (event.getY(it) * 480 / view.height.coerceAtLeast(1)).toInt().coerceIn(0, 479))
                                        }
                                        val touch = ProjectionTouch(event.eventTime * 1000000L, event.actionMasked,
                                            event.actionIndex.coerceIn(pointers.indices), pointers)
                                        if (event.actionMasked == 0) accepted = player.touch(touch)
                                        else if (accepted) player.touch(touch)
                                        if (event.actionMasked == 1) view.performClick()
                                        if (event.actionMasked == 1 || event.actionMasked == 3) accepted = false
                                        true
                                    }
                                }
                            }, modifier = Modifier.size(width, height))
                        }
                        if (status.message != "Android Auto") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (automatic) TextButton(onClick = { request(Action.DISCOVERABLE) }) { Text("Сопрячь телефон") }
                                TextButton(onClick = { request(Action.MICROPHONE) }) { Text("Разрешить микрофон") }
                            }
                        }
                    }
                }
                val peer = status.peer
                if (peer != null) AlertDialog(onDismissRequest = player::stop,
                    title = { Text(if (peer.changed) "Сертификат изменился" else "Сертификат телефона") },
                    text = { Text("Подтверди соединение со своим телефоном.\nSHA-256\n" + peer.fingerprint.chunked(32).joinToString("\n")) },
                    confirmButton = { TextButton(onClick = player::trustPeer) { Text("Подтвердить и подключить") } },
                    dismissButton = { TextButton(onClick = player::stop) { Text("Отмена") } })
                rationale?.let { action ->
                    AlertDialog(onDismissRequest = { rationale = null },
                        text = { Text(when (action) {
                            Action.CONNECT -> "Доступ к устройствам поблизости нужен для Bluetooth и создания Wi-Fi сети между магнитолой и телефоном."
                            Action.MICROPHONE -> "Микрофон нужен для голосовых команд Android Auto. Запись начинается только по запросу телефона и останавливается при выходе."
                            Action.DISCOVERABLE -> "Видимость Bluetooth позволит телефону найти магнитолу для сопряжения."
                        }) },
                        confirmButton = { TextButton(onClick = {
                            rationale = null; pending = action; permissions.launch(missing(action))
                        }) { Text("Продолжить") } },
                        dismissButton = { TextButton(onClick = { rationale = null }) { Text("Отмена") } })
                }
                if (settingsNeeded) AlertDialog(onDismissRequest = { settingsNeeded = false },
                    text = { Text("Доступ выключен. Разреши его в настройках OpenAuto и затем нажми «Подключить».") },
                    confirmButton = { TextButton(onClick = {
                        settingsNeeded = false
                        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { notice = "Открой разрешения OpenAuto в системных настройках." }
                    }) { Text("Настройки") } },
                    dismissButton = { TextButton(onClick = { settingsNeeded = false }) { Text("Позже") } })
                notice?.let { message -> AlertDialog(onDismissRequest = { notice = null },
                    text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Закрыть") } }) }
            }
        }
        if (missing(Action.CONNECT).isEmpty()) player.connect()
        else if (savedInstanceState == null) request(Action.CONNECT)
    }

    private fun adjustVolume(up: Boolean) {
        try {
            getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) { notice = "Громкость управляется штатным усилителем магнитолы." }
    }

    override fun onStop() {
        if (!isChangingConfigurations) player.stop()
        super.onStop()
    }
}
