package com.github.lfdk.OAM.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system binds here after the user grants notification access. Notification contents are not read. */
class HeadUnitMediaService : android.service.notification.NotificationListenerService()

enum class MediaCommand { PLAY, PAUSE, PREVIOUS, NEXT }

data class HeadUnitMediaSource(
    val id: String,
    val appName: String,
    val title: String,
    val artist: String,
    val artwork: Bitmap?,
    val playing: Boolean,
    val actions: Long,
    val token: MediaSession.Token
) {
    fun supports(command: MediaCommand): Boolean = when (command) {
        MediaCommand.PLAY -> actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L
        MediaCommand.PAUSE -> actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
        MediaCommand.PREVIOUS -> actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        MediaCommand.NEXT -> actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    }
}

data class HeadUnitMediaState(
    val accessEnabled: Boolean = false,
    val sources: List<HeadUnitMediaSource> = emptyList(),
    val error: String? = null
)

/** Only controls sessions published on the head unit, never phone sessions directly. */
class HeadUnitMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val component by lazy { ComponentName(app, HeadUnitMediaService::class.java) }

    suspend fun read(): HeadUnitMediaState = withContext(Dispatchers.IO) {
        if (app.packageName !in NotificationManagerCompat.getEnabledListenerPackages(app)) {
            return@withContext HeadUnitMediaState()
        }
        try {
            val manager = app.getSystemService(MediaSessionManager::class.java)
                ?: return@withContext HeadUnitMediaState(true, error = "Медиасервис недоступен.")
            val sources = manager.getActiveSessions(component)
                .filter { it.packageName != app.packageName }
                .map { controller ->
                    val metadata = controller.metadata
                    val playback = controller.playbackState
                    val label = try {
                        app.packageManager.getApplicationLabel(
                            app.packageManager.getApplicationInfo(controller.packageName, 0)).toString()
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        controller.packageName
                    }
                    HeadUnitMediaSource(
                        id = "${controller.packageName}:${controller.sessionToken.hashCode()}",
                        appName = label,
                        title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Название трека недоступно",
                        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                        artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                        playing = playback?.state == PlaybackState.STATE_PLAYING,
                        actions = playback?.actions ?: 0L, token = controller.sessionToken
                    )
                }
            HeadUnitMediaState(true, sources)
        } catch (_: SecurityException) {
            HeadUnitMediaState(error = "Нет доступа к медиасессиям. Проверь разрешение в настройках.")
        }
    }

    fun send(source: HeadUnitMediaSource, command: MediaCommand): Result<Unit> = runCatching {
        check(app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)) {
            "Доступ к управлению плеерами выключен. Проверь настройки приложения."
        }
        check(source.supports(command)) { "Источник не поддерживает эту команду." }
        val controls = MediaController(app, source.token).transportControls
        when (command) {
            MediaCommand.PLAY -> controls.play()
            MediaCommand.PAUSE -> controls.pause()
            MediaCommand.PREVIOUS -> controls.skipToPrevious()
            MediaCommand.NEXT -> controls.skipToNext()
        }
    }
}
