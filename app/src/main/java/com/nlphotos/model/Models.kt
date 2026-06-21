package com.nlphotos.model
object Models {
    val CLIP_VIT_B32 = ModelDescriptor(
        name = "clip-vit-b32-fp16",
        imageEncoderAsset = "models/clip-vit-b32/image_encoder.fp16.onnx",
        textEncoderAsset = "models/clip-vit-b32/text_encoder.fp16.onnx",
        vocabAsset = "models/clip-vit-b32/vocab.json",
        mergesAsset = "models/clip-vit-b32/merges.txt",
        inputResolution = 224,
        pixelMean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
        pixelStd = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
        embeddingDim = 512,
        contextLength = 77,
    )
}
