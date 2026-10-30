package com.github.lfdk.OAM.projection

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android output adapters. Codec and AudioTrack are used only by their dedicated worker. */
internal class ProjectionOutputs(
    context: Context, surface: Surface,
    private val rendered: () -> Unit,
    private val failed: (Exception) -> Unit,
    private val focusChanged: (Int) -> Unit
) : ProjectionMediaSink {
    private data class Packet(val bytes: ByteArray, val pts: Long, val config: Boolean, val done: () -> Unit, val generation: Int)
    override val microphone: ProjectionMicrophoneSource? =
        if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) ProjectionMicrophoneCapture(context.applicationContext) else null
    private val closed = AtomicBoolean()
    private val videoGeneration = AtomicInteger()
    private val videoBytes = AtomicInteger()
    private val video = ArrayBlockingQueue<Packet>(4)
    private val audio = (3..5).associateWith { ArrayBlockingQueue<Packet>(8) }
    private val tracks = ConcurrentHashMap<Int, AudioTrack>()
    private val generations = (3..5).associateWith { AtomicInteger() }
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val allowed = AtomicBoolean()
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (closed.get()) return@setOnAudioFocusChangeListener
            allowed.set(change == AudioManager.AUDIOFOCUS_GAIN)
            tracks.values.forEach { track -> runCatching { track.setVolume(if (allowed.get()) 1f else 0f) } }
            focusChanged(if (allowed.get()) 1 else 3)
        }, Handler(Looper.getMainLooper())).build()
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val firstFrame = AtomicBoolean()
    private val workers: List<Thread>

    init {
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 800, 480).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            }, surface, null, 0)
            codec.start()
        } catch (failure: Exception) { codec.release(); throw failure }
        workers = listOf(Thread({ decodeVideo() }, "OAM-video")) +
            audio.map { (channel, queue) -> Thread({ playAudio(channel, queue) }, "OAM-audio-$channel") }
        workers.forEach { it.start() }
    }

    private fun decodeVideo() {
        var pending: Packet? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get()) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index < 0) break
                    codec.releaseOutputBuffer(index, info.size > 0)
                    if (info.size > 0 && firstFrame.compareAndSet(false, true)) rendered()
                }
                if (pending == null) pending = video.poll(10, TimeUnit.MILLISECONDS)
                val packet = pending ?: continue
                if (packet.generation != videoGeneration.get()) {
                    videoBytes.addAndGet(-packet.bytes.size); pending = null; continue
                }
                val index = codec.dequeueInputBuffer(10000)
                if (index < 0) continue
                val input = codec.getInputBuffer(index) ?: throw IOException("Decoder has no input buffer")
                if (input.capacity() < packet.bytes.size) throw IOException("Video access unit exceeds decoder capacity")
                input.clear(); input.put(packet.bytes)
                codec.queueInputBuffer(index, 0, packet.bytes.size, packet.pts,
                    if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                videoBytes.addAndGet(-packet.bytes.size)
                packet.done()
                pending = null
            }
        } catch (failure: Exception) { if (!closed.get()) failed(failure) }
        finally { runCatching { codec.stop() }; codec.release() }
    }

    private fun playAudio(channel: Int, queue: ArrayBlockingQueue<Packet>) {
        var track: AudioTrack? = null
        var observed = generations.getValue(channel).get()
        try {
            while (!closed.get()) {
                val latest = generations.getValue(channel).get()
                if (observed != latest) {
                    track?.pause(); track?.flush(); track?.play()
                    observed = latest
                }
                val packet = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (packet.generation != generations.getValue(channel).get()) continue
                if (!allowed.get()) audioFocus(1)
                if (allowed.get()) {
                    if (track == null) {
                        val rate = if (channel == 3) 48000 else 16000
                        val mask = if (channel == 3) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                        if (minimum <= 0) throw IOException("PCM output format is unsupported")
                        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(if (channel == 3) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(if (channel == 3) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(mask).build()).setBufferSizeInBytes(maxOf(minimum * 2, rate / 5 * (if (channel == 3) 4 else 2)))
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                        if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("AudioTrack initialization failed")
                        tracks[channel] = track
                        track.play()
                    }
                    var offset = 0
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (offset < packet.bytes.size && !closed.get() && allowed.get() &&
                        packet.generation == generations.getValue(channel).get()) {
                        val written = track.write(packet.bytes, offset, packet.bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) throw IOException("AudioTrack write failed: $written")
                        offset += written
                        if (System.nanoTime() > deadline) throw IOException("Audio output stalled")
                        if (written == 0) Thread.sleep(2)
                    }
                }
                packet.done()
            }
        } catch (failure: Exception) { if (!closed.get()) failed(failure) }
        finally { tracks.remove(channel); runCatching { track?.stop() }; track?.release() }
    }

    override fun video(bytes: ByteArray, timestampUs: Long, codecConfig: Boolean, completed: () -> Unit) {
        if (closed.get()) throw IOException("Video output closed")
        val reserved = videoBytes.addAndGet(bytes.size)
        if (reserved > 8 * 1024 * 1024 || !video.offer(Packet(bytes, timestampUs, codecConfig, completed, videoGeneration.get()))) {
            videoBytes.addAndGet(-bytes.size)
            throw IOException("Video decoder backlog")
        }
    }

    override fun audio(channel: Int, bytes: ByteArray, timestampUs: Long, completed: () -> Unit) {
        if (closed.get() || bytes.size > 65536 || audio[channel]?.offer(
                Packet(bytes, timestampUs, false, completed, generations.getValue(channel).get())) != true) {
            throw IOException("Audio output backlog")
        }
    }

    override fun stop(channel: Int) {
        if (channel == 1) {
            videoGeneration.incrementAndGet()
            while (true) { val packet = video.poll() ?: break; videoBytes.addAndGet(-packet.bytes.size) }
        } else {
            generations[channel]?.incrementAndGet()
            audio[channel]?.clear()
            // The owning worker pauses and flushes AudioTrack before the next session.
        }
    }

    @Synchronized override fun audioFocus(request: Int): Int {
        if (closed.get()) return 3
        if (request == 4) {
            allowed.set(false)
            tracks.values.forEach { runCatching { it.setVolume(0f) } }
            manager?.abandonAudioFocusRequest(this.request); return 3
        }
        if (request == 0 || request !in 1..3) return if (allowed.get()) 1 else 3
        if (allowed.get()) return 1
        val result = manager?.requestAudioFocus(this.request)
        allowed.set(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        tracks.values.forEach { runCatching { it.setVolume(if (allowed.get()) 1f else 0f) } }
        return if (allowed.get()) 1 else 3
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            microphone?.close()
            synchronized(this) {
                allowed.set(false)
                manager?.abandonAudioFocusRequest(request)
            }
            workers.forEach { it.interrupt() }
            workers.forEach { if (it !== Thread.currentThread()) it.join(1000) }
            video.clear(); audio.values.forEach { it.clear() }
        }
    }
}
