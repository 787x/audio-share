package io.github.mkckr0.audio_share_app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.datastore.preferences.core.floatPreferencesKey
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.AudioConfigKeys
import io.github.mkckr0.audio_share_app.model.OutputMode
import io.github.mkckr0.audio_share_app.model.audioConfigDataStore
import io.github.mkckr0.audio_share_app.model.getFloat
import io.github.mkckr0.audio_share_app.pb.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer

internal const val OUTPUT_LOG = "AudioOutputLab"

internal class PlaybackOutput(private val context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var input: Client.AudioFormat
    private lateinit var selected: OutputMode
    private lateinit var active: OutputMode
    private var track: AudioTrack? = null
    private var enhancer: LoudnessEnhancer? = null
    private var stream = 0L
    private var packed: ByteBuffer? = null
    private var volume = 1f
    private var bufferScale = 1
    private var loudness = 0
    private var lastRoute: String? = null
    private var nextRouteCheck = 0L
    private var requestedDeviceId = 0
    private var bytesWritten = 0L

    suspend fun start(format: Client.AudioFormat) {
        val config = context.audioConfigDataStore.data.first()
        selected = OutputMode.fromPreference(config[OutputMode.key])
        input = format
        volume = config[floatPreferencesKey(AudioConfigKeys.VOLUME)] ?: context.getFloat(R.string.default_volume)
        bufferScale = (config[floatPreferencesKey(AudioConfigKeys.BUFFER_SCALE)]
            ?: context.getFloat(R.string.default_buffer_scale)).toInt()
        loudness = (config[floatPreferencesKey(AudioConfigKeys.LOUDNESS_ENHANCER)]
            ?: context.getFloat(R.string.default_loudness_enhancer)).toInt()
        Log.i(OUTPUT_LOG, "event=selected selected=$selected inputRate=${input.sampleRate} " +
            "inputFormat=${input.encoding} inputChannels=${input.channels}")
        try {
            open(selected)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            fallback(error)
        } catch (error: LinkageError) {
            fallback(error)
        }
    }

    private fun fallback(error: Throwable) {
        Log.e(OUTPUT_LOG, "event=output_failed selected=$selected active=$active", error)
        close()
        if (active == OutputMode.ORIGINAL) throw error
        Log.w(OUTPUT_LOG, "event=fallback selected=$selected active=ORIGINAL reason=${error.message}")
        open(OutputMode.ORIGINAL)
    }

    private fun open(mode: OutputMode) {
        active = mode
        if (mode != OutputMode.ORIGINAL) {
            Log.i(OUTPUT_LOG, "event=experiment_target mode=$mode rate=44100 format=PCM24_PACKED channels=2")
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "PCM24 experiment requires Android API 31" }
            check(input.encoding == Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT &&
                input.sampleRate == 44100 && input.channels == 2) { "Experiment requires Float32 / 44100 Hz / stereo input" }
            if (mode == OutputMode.AAUDIO_EXCLUSIVE) {
                openAAudio()
                return
            }
        }
        openTrack(mode == OutputMode.TRACK_PCM24)
    }

    @RequiresApi(31)
    private fun openAAudio() {
        val bluetooth = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isBluetoothMedia() }
        requestedDeviceId = bluetooth.singleOrNull()?.id ?: 0
        Log.i(OUTPUT_LOG, "event=api_request mode=$active rate=44100 format=PCM24_PACKED channels=2 " +
            "sharing=EXCLUSIVE performance=LOW_LATENCY preferredDeviceId=$requestedDeviceId " +
            "bluetoothCandidates=${bluetooth.map { it.id }} softwareGain=$volume")
        stream = AAudioNative.open(requestedDeviceId)
        check(stream != 0L) { "AAudio open/start failed or stream format differed; see native log" }
        reportRoute()
    }

    private fun openTrack(pcm24: Boolean) {
        val encoding = if (pcm24) AudioFormat.ENCODING_PCM_24BIT_PACKED else when (input.encoding) {
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            }
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                AudioFormat.ENCODING_PCM_32BIT
            }
            else -> error("Unsupported input encoding: ${input.encoding}")
        }
        val mask = when (input.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> error("Unsupported channel count: ${input.channels}")
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setChannelMask(mask)
            .setSampleRate(input.sampleRate)
            .build()
        Log.i(OUTPUT_LOG, "event=api_request mode=$active rate=${input.sampleRate} " +
            "format=${encodingName(encoding)} channels=${input.channels} route=system_default")
        if (pcm24 && Build.VERSION.SDK_INT >= 33) {
            Log.i(OUTPUT_LOG, "event=direct_capability mode=$active " +
                "flags=${AudioManager.getDirectPlaybackSupport(format, attributes)}")
        }
        val minimum = AudioTrack.getMinBufferSize(input.sampleRate, mask, encoding)
        check(minimum > 0) { "AudioTrack.getMinBufferSize returned $minimum" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * bufferScale)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = output
        check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" }
        Log.i(OUTPUT_LOG, "event=api_actual mode=$active rate=${output.sampleRate} " +
            "format=${encodingName(output.audioFormat)} channels=${output.channelCount} session=${output.audioSessionId}")
        check(!pcm24 || (output.sampleRate == 44100 && output.audioFormat == encoding && output.channelCount == 2)) {
            "AudioTrack stream format differed"
        }
        output.setVolume(volume)
        if (!pcm24 && loudness > 0) {
            val effect = LoudnessEnhancer(output.audioSessionId)
            enhancer = effect
            effect.setTargetGain(loudness)
            effect.enabled = true
        }
        output.play()
        reportRoute()
    }

    suspend fun write(inputData: ByteBuffer) {
        if (active != OutputMode.ORIGINAL && inputData.remaining() % FLOAT_STEREO_FRAME_BYTES != 0) {
            Log.w(OUTPUT_LOG, "event=drop_unaligned_packet mode=$active bytes=${inputData.remaining()}")
            return
        }
        val data = if (active == OutputMode.ORIGINAL) inputData else {
            val size = inputData.remaining() / FLOAT_STEREO_FRAME_BYTES * PCM24_STEREO_FRAME_BYTES
            val buffer = packed?.takeIf { it.capacity() >= size }
                ?: ByteBuffer.allocateDirect(size).also { packed = it }
            buffer.clear()
            packFloatStereo24(inputData, buffer, if (stream != 0L) volume else 1f)
            buffer.flip()
            buffer
        }

        try {
            var lastProgress = SystemClock.elapsedRealtime()
            while (data.hasRemaining()) {
                currentCoroutineContext().ensureActive()
                val written = if (stream != 0L) {
                    val frames = AAudioNative.write(stream, data, data.position(), data.remaining() / PCM24_STEREO_FRAME_BYTES)
                    check(frames >= 0) { "AAudio write returned $frames" }
                    (frames * PCM24_STEREO_FRAME_BYTES).also { data.position(data.position() + it) }
                } else {
                    track!!.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                }
                check(written >= 0) { "AudioTrack write returned $written" }
                if (written == 0) {
                    check(SystemClock.elapsedRealtime() - lastProgress < 500) { "Output stalled for 500 ms" }
                    delay(2)
                } else {
                    if (bytesWritten == 0L) {
                        Log.i(OUTPUT_LOG, "event=first_write selected=$selected active=$active bytes=$written")
                    }
                    bytesWritten += written
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            if (SystemClock.elapsedRealtime() >= nextRouteCheck) reportRoute()
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            Log.w(OUTPUT_LOG, "event=write_interrupted mode=$active unwrittenBytes=${data.remaining()}")
            fallback(error)
        }
    }

    private fun reportRoute() {
        nextRouteCheck = SystemClock.elapsedRealtime() + 1000
        val deviceId = if (stream != 0L) AAudioNative.deviceId(stream) else track?.routedDevice?.id ?: 0
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        val route = "deviceId=$deviceId type=${device?.type} name=${device?.productName} " +
            "bluetooth=${device?.isBluetoothMedia()} preferredDeviceId=$requestedDeviceId"
        if (lastRoute == route) return
        lastRoute = route
        val result = when {
            device == null -> "unknown"
            !device.isBluetoothMedia() -> "non_bluetooth"
            requestedDeviceId != 0 && deviceId != requestedDeviceId -> "different_bluetooth_device"
            else -> "bluetooth"
        }
        Log.i(OUTPUT_LOG, "event=api_route mode=$active result=$result $route")
    }

    fun close() {
        if (stream != 0L) {
            Log.i(OUTPUT_LOG, "event=close mode=$active bytesWritten=$bytesWritten xruns=${AAudioNative.xRuns(stream)}")
            AAudioNative.close(stream)
            stream = 0
        }
        enhancer?.release()
        enhancer = null
        track?.let {
            val underruns = if (Build.VERSION.SDK_INT >= 24) it.underrunCount else null
            Log.i(OUTPUT_LOG, "event=close mode=$active bytesWritten=$bytesWritten session=${it.audioSessionId} underruns=$underruns")
            it.release()
        }
        track = null
        lastRoute = null
        requestedDeviceId = 0
        bytesWritten = 0L
    }
}

private fun AudioDeviceInfo.isBluetoothMedia() =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
        type == AudioDeviceInfo.TYPE_HEARING_AID

private fun encodingName(encoding: Int) = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT32"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24_PACKED"
    else -> encoding.toString()
}

internal object AAudioNative {
    init {
        System.loadLibrary("audio_share_aaudio")
    }

    external fun open(deviceId: Int): Long
    external fun write(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int): Int
    external fun deviceId(handle: Long): Int
    external fun xRuns(handle: Long): Int
    external fun close(handle: Long)
}
