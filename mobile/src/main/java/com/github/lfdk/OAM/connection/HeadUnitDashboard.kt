package com.github.lfdk.OAM.connection

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.lfdk.OAM.media.*
import com.github.lfdk.OAM.projection.ProjectionActivity
import com.github.lfdk.OAM.projection.WirelessProbeViewModel
import com.github.lfdk.OAM.projection.WirelessProjectionPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val Accent = Color(0xFFB9F476)
private val Muted = Color(0xFFA5AFA8)
val HeadUnitColors = darkColorScheme(primary = Accent, onPrimary = Color(0xFF14200B),
    background = Color(0xFF0D110F), surface = Color(0xFF19211C))

@Composable
fun HeadUnitDashboard(projection: WirelessProbeViewModel? = null) {
    val context = LocalContext.current
    val repository = remember(context) { HeadUnitRepository(context) }
    val mediaRepository = remember(context) { HeadUnitMediaRepository(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preview = LocalInspectionMode.current
    DisposableEffect(lifecycle, projection, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && context.findActivity()?.isChangingConfigurations != true) {
                projection?.cancel()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var state by remember { mutableStateOf<HeadUnitState?>(
        if (preview) HeadUnitState(volume = VolumeState(8, 25, false)) else null) }
    var media by remember { mutableStateOf(HeadUnitMediaState()) }
    var auto by rememberSaveable { mutableStateOf(false) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showConnections by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var rationale by remember { mutableStateOf(false) }
    var permissionSettings by remember { mutableStateOf(false) }

    fun settings(action: String, data: Uri? = null) {
        try { context.startActivity(Intent(action, data)) }
        catch (_: ActivityNotFoundException) { message = "Открой этот раздел через настройки производителя магнитолы." }
        catch (_: SecurityException) { message = "Прошивка ограничивает открытие этого раздела настроек." }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        val activity = context.findActivity()
        if (!granted) {
            if (activity != null && Build.VERSION.SDK_INT >= 31 &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.BLUETOOTH_CONNECT)) {
                permissionSettings = true
            } else message = "Список устройств недоступен без разрешения. Остальные функции можно использовать."
        }
    }
    fun requestBluetooth() {
        val activity = context.findActivity()
        when {
            repository.hasBluetoothPermission() -> refresh++
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.BLUETOOTH_CONNECT) -> rationale = true
            else -> permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    LaunchedEffect(lifecycle, refresh, preview) {
        if (!preview) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                state = repository.read()
                media = mediaRepository.read()
                delay(2000)
            }
        }
    }
    val source = media.sources.firstOrNull { it.id == sourceId } ?: media.sources.singleOrNull()
    fun command(command: MediaCommand) {
        source?.let {
            mediaRepository.send(it, command).onFailure { failure -> message = failure.message ?: "Источник недоступен." }
            refresh++
        }
    }
    fun volume(level: Int) {
        repository.setVolume(level).onSuccess { result -> state = state?.copy(volume = result) }
            .onFailure { message = it.message ?: "Громкость недоступна." }
    }
    fun connectAuto() {
        try { context.startActivity(Intent(context, ProjectionActivity::class.java).putExtra("wireless", true)) }
        catch (_: ActivityNotFoundException) { message = "Экран Android Auto недоступен." }
        catch (_: SecurityException) { message = "Прошивка запретила запуск Android Auto." }
    }
    val modes: @Composable RowScope.() -> Unit = {
        ModeButton("Bluetooth", !auto, { projection?.cancel(); auto = false; showSources = false }, Modifier.weight(1f))
        ModeButton("Android Auto", auto, { auto = true; showSources = false }, Modifier.weight(1f))
    }

    Scaffold { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().background(HeadUnitColors.background).padding(insets)) {
            val oneRowHeader = maxWidth >= 720.dp
            val dense = maxHeight < 480.dp
            Column(Modifier.fillMaxSize().padding(if (dense) 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OPEN / AUTO", color = Accent, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, maxLines = 1, modifier = if (oneRowHeader) Modifier else Modifier.weight(1f))
                    if (oneRowHeader) Row(Modifier.weight(1f).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), content = modes)
                    TextButton(onClick = { showSources = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (auto) "Диагностика" else "Источник", maxLines = 1)
                    }
                    OutlinedButton(onClick = { showConnections = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Подключение", maxLines = 1)
                    }
                }
                if (!oneRowHeader) Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), content = modes)

                Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF141B17),
                    shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFF26332A))) {
                    if (auto) {
                        Column(Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!dense) Text("ANDROID AUTO", color = Accent, fontSize = 12.sp, letterSpacing = 2.sp)
                            Text("Телефон на большом экране", fontWeight = FontWeight.SemiBold,
                                fontSize = if (dense) 22.sp else 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!dense) Text("Карты, музыка и голосовые команды — без проводов.", color = Muted, maxLines = 2)
                            Button(onClick = ::connectAuto, enabled = projection?.running != true,
                                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 56.dp)) {
                                Text("Подключить телефон")
                            }
                        }
                    } else {
                        MusicPanel(source, ::command, { showSources = true })
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = HeadUnitColors.surface) {
                    VolumeControls(state?.volume, ::volume) { settings(Settings.ACTION_SOUND_SETTINGS) }
                }
            }
        }
    }

    if (showSources) DashboardDialog(if (auto) "Диагностика Android Auto" else "Источник музыки",
        onDismiss = { showSources = false }) {
        if (auto) WirelessProjectionPanel(projection)
        else {
            when {
                !media.accessEnabled -> {
                    Text("Разреши управление плеерами, чтобы видеть трек и обложку. Содержимое уведомлений не читается.", color = Muted)
                    Button(onClick = {
                        showSources = false
                        settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Разрешить управление") }
                }
                media.sources.isEmpty() -> Text("Запусти музыку на телефоне и штатный Bluetooth-плеер магнитолы. Источники появятся здесь.", color = Muted)
                else -> media.sources.forEach { item ->
                    FilterChip(selected = source?.id == item.id, onClick = { sourceId = item.id; showSources = false },
                        label = { Text("${item.appName} • ${item.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp))
                }
            }
            media.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showConnections) DashboardDialog("Подключение", onDismiss = { showConnections = false }) {
        val bluetooth = state?.bluetooth
        Text("Bluetooth • ${radioLabel(bluetooth?.status)}", color = Accent)
        bluetooth?.message?.let { Text(it, color = Muted) }
        if (bluetooth?.status == RadioStatus.PERMISSION_REQUIRED) {
            Button(onClick = ::requestBluetooth, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Показать сопряжённые устройства")
            }
        }
        if (bluetooth?.status == RadioStatus.ON) {
            if (bluetooth.devices.isEmpty()) Text("Сопряги телефон в настройках Bluetooth.", color = Muted)
            bluetooth.devices.forEach { Text("${it.name} • ${it.address.takeLast(5)}", color = Muted) }
        }
        OutlinedButton(onClick = { settings(Settings.ACTION_BLUETOOTH_SETTINGS) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Настройки Bluetooth") }
        Text("Wi-Fi • ${radioLabel(state?.wifi)}", color = Muted)
        OutlinedButton(onClick = { settings(Settings.ACTION_WIFI_SETTINGS) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Настройки Wi-Fi") }
        TextButton(onClick = { message = repository.deviceReport() }) { Text("Информация об устройстве") }
    }
    if (rationale) AlertDialog(onDismissRequest = { rationale = false },
        title = { Text("Сопряжённые устройства") },
        text = { Text("Доступ к устройствам поблизости нужен для списка сопряжённых телефонов. Геолокация не используется.") },
        confirmButton = { TextButton(onClick = {
            rationale = false
            if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("Отмена") } })
    if (permissionSettings) AlertDialog(onDismissRequest = { permissionSettings = false },
        title = { Text("Доступ к Bluetooth выключен") },
        text = { Text("Разреши доступ к устройствам поблизости в настройках приложения.") },
        confirmButton = { TextButton(onClick = {
            permissionSettings = false
            settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }) { Text("Настройки приложения") } },
        dismissButton = { TextButton(onClick = { permissionSettings = false }) { Text("Позже") } })
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("OpenAuto") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Закрыть") } })
    }
}

@Composable
private fun MusicPanel(source: HeadUnitMediaSource?, command: (MediaCommand) -> Unit, chooseSource: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val compact = maxHeight < 280.dp
        val tiny = maxHeight < 200.dp
        val artworkSize = if (tiny) 56.dp else if (source?.artwork == null) 72.dp else if (compact) 112.dp else 160.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp, Alignment.CenterVertically)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                AlbumCover(source, Modifier.size(artworkSize))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!compact) Text(source?.appName ?: "BLUETOOTH-МУЗЫКА", color = Accent,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source?.title ?: "Пока ничего не играет",
                        fontSize = if (compact) 24.sp else 28.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    Text(source?.artist?.ifBlank { "Исполнитель недоступен" } ?: "Включи музыку на телефоне.",
                        color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!tiny) TextButton(onClick = chooseSource, contentPadding = PaddingValues(horizontal = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (source == null) "Выбрать источник" else "Сменить источник")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { command(MediaCommand.PREVIOUS) },
                    enabled = source?.supports(MediaCommand.PREVIOUS) == true,
                    modifier = Modifier.weight(1f).heightIn(min = if (compact) 56.dp else 64.dp)) { Text("Назад") }
                val action = if (source?.playing == true) MediaCommand.PAUSE else MediaCommand.PLAY
                Button(onClick = { command(action) }, enabled = source?.supports(action) == true,
                    modifier = Modifier.weight(1f).heightIn(min = if (compact) 56.dp else 64.dp)) {
                    Text(if (source?.playing == true) "Пауза" else "Пуск", maxLines = 1)
                }
                OutlinedButton(onClick = { command(MediaCommand.NEXT) },
                    enabled = source?.supports(MediaCommand.NEXT) == true,
                    modifier = Modifier.weight(1f).heightIn(min = if (compact) 56.dp else 64.dp)) { Text("Далее") }
            }
        }
    }
}

@Composable
private fun VolumeControls(current: VolumeState?, volume: (Int) -> Unit, soundSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (current == null || current.fixed || current.maximum <= 0) {
            Column(Modifier.weight(1f)) {
                Text("Громкость", fontSize = 14.sp)
                Text(if (current == null) "Проверяем устройство…" else "Управляется системным усилителем",
                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = soundSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Настройки звука") }
        } else {
            var slider by remember(current.level, current.maximum) { mutableFloatStateOf(current.level.toFloat()) }
            OutlinedButton(onClick = { volume(current.level - 1) }, enabled = current.level > 0,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(56.dp)) { Text("−", fontSize = 24.sp) }
            Column(Modifier.weight(1f)) {
                Text("Громкость • ${current.level} / ${current.maximum}", color = Muted, fontSize = 12.sp)
                Slider(value = slider, onValueChange = { slider = it },
                    onValueChangeFinished = { volume(slider.roundToInt()) }, valueRange = 0f..current.maximum.toFloat())
            }
            OutlinedButton(onClick = { volume(current.level + 1) }, enabled = current.level < current.maximum,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(56.dp)) { Text("+", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun DashboardDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } })
}

@Composable
private fun ModeButton(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp)) { Text(title, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp)) { Text(title, maxLines = 1) }
}

@Composable
private fun AlbumCover(source: HeadUnitMediaSource?, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), modifier = modifier, color = Color(0xFF26362C)) {
        val bitmap = source?.artwork
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Обложка трека",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(contentAlignment = Alignment.Center) { Text("♪", fontSize = 34.sp, color = Accent) }
    }
}

private fun radioLabel(status: RadioStatus?): String = when (status) {
    null -> "проверяем…"
    RadioStatus.ON -> "включён"
    RadioStatus.OFF -> "выключен"
    RadioStatus.PERMISSION_REQUIRED -> "нужен доступ"
    RadioStatus.UNAVAILABLE -> "недоступен приложению"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(name = "Head unit", widthDp = 1024, heightDp = 600)
@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Preview(name = "Low head unit", widthDp = 1024, heightDp = 420)
@Preview(name = "Automotive content", widthDp = 1408, heightDp = 620)
@Preview(name = "Small head unit", widthDp = 800, heightDp = 420)
@Composable
private fun HeadUnitPreview() {
    MaterialTheme(colorScheme = HeadUnitColors) { HeadUnitDashboard() }
}
