package com.github.lfdk.OAM.projection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Foreground-only capture, owned by a projection screen; no samples are stored. */
internal class ProjectionMicrophoneCapture(private val context: Context) : ProjectionMicrophoneSource {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "OAM-microphone") }
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean()

    override fun start(ready: (Boolean) -> Unit, data: (ByteArray, Long) -> Unit, failed: () -> Unit) {
        val token = generation.incrementAndGet()
        if (closed.get()) { ready(false); return }
        worker.execute {
            var recorder: AudioRecord? = null
            var started = false
            try {
                if (closed.get() || generation.get() != token) return@execute
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ready(false); return@execute
                }
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) throw IOException("Microphone PCM format is unsupported")
                recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone initialization failed")
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")
                started = true
                ready(true)
                val chunk = ByteArray(3200) // 100 ms, bounded even if a peer stops returning credits.
                var used = 0
                var stamp = System.nanoTime() / 1000
                while (!closed.get() && generation.get() == token) {
                    val count = recorder.read(chunk, used, chunk.size - used, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0 || count % 2 != 0) throw IOException("Microphone read failed: $count")
                    used += count
                    if (used == chunk.size) {
                        data(chunk.copyOf(), stamp)
                        used = 0; stamp = System.nanoTime() / 1000
                    }
                    if (count == 0) Thread.sleep(5)
                }
            } catch (_: Exception) {
                if (!closed.get() && generation.get() == token) {
                    if (started) failed() else ready(false)
                }
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
            }
        }
    }

    override fun stop() { generation.incrementAndGet() }
    override fun close() {
        if (closed.compareAndSet(false, true)) { stop(); worker.shutdownNow() }
    }
}
