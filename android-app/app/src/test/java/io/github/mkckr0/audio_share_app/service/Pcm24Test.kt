package io.github.mkckr0.audio_share_app.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm24Test {
    @Test
    fun clipsAndPacksInterleavedStereoInLittleEndian() {
        val input = floats(-2f, 2f, -1f, 1f, 0.5f, -0.5f, 1f / 8388608, -1f / 8388608,
            Float.NaN, 0f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        input.order(ByteOrder.BIG_ENDIAN)
        val output = ByteBuffer.allocate(36)
        packFloatStereo24(input, output)
        assertEquals(0, input.position())
        assertEquals(output.capacity(), output.position())
        assertArrayEquals(byteArrayOf(
            0, 0, -128, -1, -1, 127,
            0, 0, -128, -1, -1, 127,
            0, 0, 64, 0, 0, -64,
            1, 0, 0, -1, -1, -1,
            0, 0, 0, 0, 0, 0,
            0, 0, -128, -1, -1, 127,
        ), output.array())
    }

    @Test
    fun respectsInputWindowAndAppliesGain() {
        val input = floats(1f, 1f, 0.5f, -0.5f, 1f, 1f)
        input.position(8)
        input.limit(16)
        val output = ByteBuffer.allocate(6)
        packFloatStereo24(input, output, gain = 0.5f)
        assertEquals(8, input.position())
        assertArrayEquals(byteArrayOf(0, 0, 32, 0, 0, -32), output.array())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialStereoFrame() {
        packFloatStereo24(floats(0.5f), ByteBuffer.allocate(6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsufficientOutputSpace() {
        packFloatStereo24(floats(0.5f, -0.5f), ByteBuffer.allocate(5))
    }

    private fun floats(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putFloat(it) }
            flip()
        }
}
