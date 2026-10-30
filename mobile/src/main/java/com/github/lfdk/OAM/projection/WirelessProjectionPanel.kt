package com.github.lfdk.OAM.projection

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

/** Wireless pairing is the main flow; the manual server path is retained for diagnostics. */
@Composable
fun WirelessProjectionPanel(model: WirelessProbeViewModel?) {
    if (model == null) {
        Text("Проверка соединения доступна при запуске приложения.")
        return
    }
    val host by model.host.collectAsState()
    val state by model.state.collectAsState()
    val running = model.running
    val context = LocalContext.current
    var rationale by remember { mutableStateOf(false) }
    var settingsNeeded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var diagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.start() else {
            val activity = context.probeActivity()
            if (activity != null && Build.VERSION.SDK_INT >= 37 &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_LOCAL_NETWORK)) {
                settingsNeeded = true
            } else notice = "Без доступа к локальной сети проверка недоступна."
        }
    }
    fun startCheck(mode: ProjectionCheckMode) {
        model.chooseMode(mode)
        if (!model.validateHost()) return
        val activity = context.probeActivity()
        when {
            model.hasNetworkAccess() -> model.start()
            activity != null && Build.VERSION.SDK_INT >= 37 &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_LOCAL_NETWORK) -> rationale = true
            Build.VERSION.SDK_INT >= 37 -> permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Беспроводной Android Auto", style = MaterialTheme.typography.titleMedium)
        Text("Подключи телефон по Bluetooth. Приложение передаст ему параметры Wi-Fi и откроет экран Android Auto.")
        Button(onClick = {
            context.startActivity(Intent(context, ProjectionActivity::class.java).putExtra("wireless", true))
        }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
            Text("Подключить телефон")
        }
        Text("Для звонков оставь телефон подключённым к штатной громкой связи магнитолы. На прошивках с отдельным Bluetooth-модулем требуется совместимый адаптер.")
        TextButton(onClick = { diagnostics = !diagnostics }) {
            Text(if (diagnostics) "Скрыть диагностику" else "Диагностика подключения")
        }
        if (diagnostics || running) {
        HorizontalDivider()
        Text("Диагностика через общую Wi-Fi сеть", style = MaterialTheme.typography.titleSmall)
        Text("Ручной способ: запусти «Сервер головного устройства» в настройках разработчика Android Auto на телефоне и введи его IP-адрес.")
        OutlinedTextField(value = host, onValueChange = model::editHost, enabled = !running,
            label = { Text("IP-адрес телефона") }, placeholder = { Text("192.168.43.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { startCheck(ProjectionCheckMode.TLS_PROBE) }, enabled = !running && host.isNotBlank(),
                modifier = Modifier.heightIn(min = 60.dp).weight(1f)) { Text("Проверить TLS") }
            OutlinedButton(onClick = { startCheck(ProjectionCheckMode.CONTROL_SESSION) }, enabled = !running && host.isNotBlank(),
                modifier = Modifier.heightIn(min = 60.dp).weight(1f)) { Text("Открыть сессию") }
        }
        Button(onClick = {
            if (model.validateHost()) context.startActivity(Intent(context, ProjectionActivity::class.java)
                .putExtra("phone_ip", host.trim()))
        }, enabled = !running && host.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
            Text("Подключить по IP")
        }
        if (running) {
            OutlinedButton(onClick = model::cancel, enabled = state != WirelessProbeState.Stopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("Отключить") }
        }
        when (val current = state) {
            WirelessProbeState.Idle -> Unit
            WirelessProbeState.Stopping -> Text("Закрываем соединение…")
            is WirelessProbeState.ControlConnected -> {
                Text("Служебная TLS-сессия открыта.")
                Text(if (current.discovered) "Возможности приёмника отправлены. Видео и аудио пока не объявлены."
                    else "Ожидаем запрос возможностей от телефона.")
            }
            is WirelessProbeState.SessionEnded -> Text(if (current.reason == ControlSessionEnd.USER_STOPPED)
                "Служебная сессия закрыта." else "Телефон завершил служебную сессию.")
            WirelessProbeState.Preparing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготавливаем ключ приёмника…") }
            WirelessProbeState.Securing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Согласовываем защищённое соединение…") }
            is WirelessProbeState.ReviewPeer -> Text("Для продолжения подтверди сертификат телефона.")
            WirelessProbeState.Connecting -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Открываем соединение по Wi-Fi…") }
            WirelessProbeState.Negotiating -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Ожидаем ответ Android Auto…") }
            WirelessProbeState.Cancelled -> Text("Проверка отменена.")
            is WirelessProbeState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
            is WirelessProbeState.Reply -> {
                val result = current.result
                Text("Защищённый обмен завершён: ${result.tls.protocol}, протокол Android Auto ${result.version.major}.${result.version.minor}.")
                Text("Проверка завершена, соединение закрыто. Сессия Android Auto ещё не запущена.")
            }
        }
        }
    }
    val review = state as? WirelessProbeState.ReviewPeer
    if (review != null) AlertDialog(
        onDismissRequest = model::cancel,
        title = { Text(if (review.changed) "Сертификат телефона изменился" else "Сертификат телефона") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Запомнить этот сертификат для телефона по адресу ${review.host}?")
            Text("SHA-256\n" + review.fingerprint.chunked(32).joinToString("\n"))
            Text("Подтверждай сертификат для своего телефона в своей Wi-Fi сети. Отпечаток сам по себе не подтверждает модель или владельца телефона.")
        } },
        confirmButton = { TextButton(onClick = model::acceptPeer) {
            Text(if (review.changed) "Заменить сохранённый" else "Запомнить и повторить")
        } },
        dismissButton = { TextButton(onClick = model::cancel) { Text("Отмена") } }
    )
    if (rationale) AlertDialog(onDismissRequest = { rationale = false },
        title = { Text("Связь по Wi-Fi") },
        text = { Text("Доступ к локальной сети нужен для соединения с указанным телефоном. Приложение не сканирует сеть.") },
        confirmButton = { TextButton(onClick = {
            rationale = false
            if (Build.VERSION.SDK_INT >= 37) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("Отмена") } })
    if (settingsNeeded) AlertDialog(onDismissRequest = { settingsNeeded = false },
        title = { Text("Доступ к локальной сети выключен") },
        text = { Text("Разреши доступ в настройках приложения, если хочешь проверить соединение с телефоном.") },
        confirmButton = { TextButton(onClick = {
            settingsNeeded = false
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            } catch (_: ActivityNotFoundException) {
                notice = "Открой разрешения OpenAuto в настройках магнитолы."
            } catch (_: SecurityException) {
                notice = "Открой разрешения OpenAuto в настройках магнитолы."
            }
        }) { Text("Настройки") } },
        dismissButton = { TextButton(onClick = { settingsNeeded = false }) { Text("Позже") } })
    notice?.let { text ->
        AlertDialog(onDismissRequest = { notice = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("Закрыть") } })
    }
}

private tailrec fun Context.probeActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.probeActivity()
    else -> null
}
