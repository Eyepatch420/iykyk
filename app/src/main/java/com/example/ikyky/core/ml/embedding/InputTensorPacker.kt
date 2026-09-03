package com.example.ikyky.core.ml.embedding

import com.example.ikyky.core.ml.model.ModelSpec

/**
 * Pure (Android-free) packing of an ARGB pixel array into the model's input
 * float layout, so the exact tensor shape / channel order / normalization can be
 * unit-tested on the JVM without a `Bitmap`.
 *
 * Layout written: NHWC, `1 × H × W × 3`, channel order **R, G, B**, each value
 * normalized per [ModelSpec.normalization].
 */
object InputTensorPacker {

    /**
     * @param argb  `width*height` pixels in `0xAARRGGBB`, row-major (top-left first)
     * @return a `FloatArray` of length `width*height*3` in NHWC RGB order
     */
    fun pack(
        argb: IntArray,
        width: Int,
        height: Int,
        normalization: ModelSpec.Normalization = ModelSpec.Normalization.MINUS_ONE_TO_ONE,
    ): FloatArray {
        require(argb.size == width * height) {
            "pixel count ${argb.size} != $width*$height"
        }
        val out = FloatArray(width * height * 3)
        var o = 0
        for (pixel in argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            out[o++] = normalize(r, normalization)
            out[o++] = normalize(g, normalization)
            out[o++] = normalize(b, normalization)
        }
        return out
    }

    fun normalize(channel: Int, normalization: ModelSpec.Normalization): Float =
        when (normalization) {
            ModelSpec.Normalization.MINUS_ONE_TO_ONE -> (channel - 127.5f) / 127.5f
            ModelSpec.Normalization.ZERO_TO_ONE -> channel / 255f
        }
}
