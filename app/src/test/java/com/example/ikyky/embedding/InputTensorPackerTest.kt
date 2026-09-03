package com.example.ikyky.embedding

import com.example.ikyky.core.ml.embedding.InputTensorPacker
import com.example.ikyky.core.ml.model.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 steps 25.2 (tensor shape), 25.3 (RGB channel ordering), 25.1 (normalization). */
class InputTensorPackerTest {

    // 2. 112 x 112 tensor shape — length is exactly W*H*3
    @Test
    fun packedLength_is112x112x3() {
        val n = 112 * 112
        val argb = IntArray(n) { 0xFF808080.toInt() }
        val packed = InputTensorPacker.pack(argb, 112, 112)
        assertEquals(112 * 112 * 3, packed.size)
    }

    // 3. RGB channel ordering — a pure-red pixel packs as (norm(255), norm(0), norm(0))
    @Test
    fun channelOrder_isRGB_notBGR() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val packed = InputTensorPacker.pack(intArrayOf(red, green, blue), 3, 1)

        val hi = (255 - 127.5f) / 127.5f      // +1
        val lo = (0 - 127.5f) / 127.5f        // -1

        // pixel 0 = red  -> R hi, G lo, B lo
        assertEquals(hi, packed[0], 1e-6f)
        assertEquals(lo, packed[1], 1e-6f)
        assertEquals(lo, packed[2], 1e-6f)
        // pixel 1 = green -> R lo, G hi, B lo
        assertEquals(lo, packed[3], 1e-6f)
        assertEquals(hi, packed[4], 1e-6f)
        assertEquals(lo, packed[5], 1e-6f)
        // pixel 2 = blue -> R lo, G lo, B hi
        assertEquals(lo, packed[6], 1e-6f)
        assertEquals(lo, packed[7], 1e-6f)
        assertEquals(hi, packed[8], 1e-6f)
    }

    // 1. normalization — mid-grey (128) maps near 0, all values within [-1, 1]
    @Test
    fun normalization_midGreyNearZero_rangeBounded() {
        val grey = 0xFF808080.toInt() // 128,128,128
        val packed = InputTensorPacker.pack(intArrayOf(grey), 1, 1)
        for (v in packed) {
            assertEquals(0.00392f, v, 1e-4f) // (128-127.5)/127.5
            assertTrue(v in -1f..1f)
        }
    }

    @Test
    fun zeroToOneNormalization_isSupported() {
        val packed = InputTensorPacker.pack(
            intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 1,
            normalization = ModelSpec.Normalization.ZERO_TO_ONE,
        )
        assertEquals(0f, packed[0], 1e-6f)
        assertEquals(1f, packed[3], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPixelCount_throws() {
        InputTensorPacker.pack(IntArray(10), 4, 4)
    }
}
