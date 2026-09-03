package com.example.ikyky.core.ml.model

/**
 * Static description of an on-device model asset. Keeps the asset path, expected
 * tensor shapes and normalization co-located and out of scattered constants.
 */
data class ModelSpec(
    val assetPath: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputChannels: Int,
    val outputDimension: Int,
    val normalization: Normalization,
) {
    /** Number of float elements in one input tensor. */
    val inputElementCount: Int get() = inputWidth * inputHeight * inputChannels

    enum class Normalization {
        /** pixel / 127.5 - 1.0  → range [-1, 1]. MobileFaceNet / ArcFace convention. */
        MINUS_ONE_TO_ONE,

        /** pixel / 255.0 → range [0, 1]. */
        ZERO_TO_ONE,
    }

    companion object {
        /**
         * MobileFaceNet (InsightFace / ArcFace-style training), MobileFaceNet_9925_9680
         * lineage, TF→TFLite (TOCO). Binary verified against the asset: input
         * tensor `input` = [1,112,112,3] float32 (NHWC), output tensor
         * `embeddings` = [1,192] float32, single subgraph.
         *
         * Provenance / redistribution basis:
         *  - Immediate binary source: github.com/MCarlomagno/FaceRecognitionAuth
         *    (`assets/mobilefacenet.tflite`) — BSD-3-Clause, © 2020 Marcos Carlomagno.
         *  - Original model/graph: github.com/sirius-ai/MobileFaceNet_TF — Apache-2.0.
         *  Both licenses permit bundling the binary with attribution. Weights were
         *  trained on public research face datasets; no separate weight licence is
         *  published upstream. See README "Face Embedding Model".
         */
        val MOBILE_FACE_NET = ModelSpec(
            assetPath = "models/mobile_face_net.tflite",
            inputWidth = 112,
            inputHeight = 112,
            inputChannels = 3,
            outputDimension = 192,
            normalization = Normalization.MINUS_ONE_TO_ONE,
        )
    }
}
