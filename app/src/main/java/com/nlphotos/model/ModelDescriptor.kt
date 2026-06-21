package com.nlphotos.model
data class ModelDescriptor(
    val name: String,
    val imageEncoderAsset: String,
    val textEncoderAsset: String,
    val vocabAsset: String,
    val mergesAsset: String,
    val inputResolution: Int,
    val pixelMean: FloatArray,
    val pixelStd: FloatArray,
    val embeddingDim: Int,
    val contextLength: Int,
)
