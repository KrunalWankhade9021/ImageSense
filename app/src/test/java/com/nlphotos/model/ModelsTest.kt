package com.nlphotos.model
import org.junit.Assert.assertEquals
import org.junit.Test
class ModelsTest {
    @Test fun clip_descriptor_has_expected_constants() {
        val d = Models.CLIP_VIT_B32
        assertEquals(224, d.inputResolution)
        assertEquals(512, d.embeddingDim)
        assertEquals(77, d.contextLength)
        assertEquals(3, d.pixelMean.size)
        assertEquals(3, d.pixelStd.size)
    }
}
