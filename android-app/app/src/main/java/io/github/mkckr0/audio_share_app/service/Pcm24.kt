package io.github.mkckr0.audio_share_app.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal const val FLOAT_STEREO_FRAME_BYTES = 8
internal const val PCM24_STEREO_FRAME_BYTES = 6

internal fun packFloatStereo24(input: ByteBuffer, output: ByteBuffer, gain: Float = 1f) {
    require(input.remaining() % FLOAT_STEREO_FRAME_BYTES == 0)
    require(output.remaining() >= input.remaining() / FLOAT_STEREO_FRAME_BYTES * PCM24_STEREO_FRAME_BYTES)
    val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    while (samples.hasRemaining()) {
        val sample = samples.float * gain
        val value = if (sample.isNaN()) 0 else {
            (sample.coerceIn(-1f, 1f) * 8388608f).roundToInt().coerceAtMost(8388607)
        }
        output.put(value.toByte())
        output.put((value shr 8).toByte())
        output.put((value shr 16).toByte())
    }
}
