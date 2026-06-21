package com.nlphotos.search
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt
class VectorMathTest {
    @Test fun normalize_gives_unit_length() {
        val n = l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1.0f, sqrt(n[0]*n[0] + n[1]*n[1]), 1e-6f)
    }
    @Test fun dot_of_identical_unit_vectors_is_one() {
        val a = l2Normalize(floatArrayOf(1f, 2f, 2f))
        assertEquals(1.0f, dot(a, a), 1e-6f)
    }
}
