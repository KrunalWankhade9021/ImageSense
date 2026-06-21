package com.nlphotos.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BpeTokenizerTest {

    private fun load() = BpeTokenizer.fromResources(
        vocab = javaClass.getResourceAsStream("/clip/vocab.json")!!,
        merges = javaClass.getResourceAsStream("/clip/merges.txt")!!,
        contextLength = 77,
    )

    private fun loadFixtures(): Map<String, IntArray> {
        val text = javaClass.getResourceAsStream("/clip/clip_token_fixtures.json")!!
            .bufferedReader().readText()
        return BpeTokenizer.parseJsonStringIntArrayMap(text)
    }

    @Test
    fun `all fixture phrases encode to exact reference ids`() {
        val tokenizer = load()
        val fixtures = loadFixtures()
        assert(fixtures.isNotEmpty()) { "Fixture file is empty" }

        for ((phrase, expected) in fixtures) {
            val actual = tokenizer.encode(phrase)
            assertEquals("Length mismatch for '$phrase'", 77, actual.size)
            assertArrayEquals("Token id mismatch for '$phrase'", expected, actual)
        }
    }

    @Test
    fun `output length is always contextLength`() {
        val tokenizer = load()
        assertEquals(77, tokenizer.encode("hello world").size)
        assertEquals(77, tokenizer.encode("").size)
    }

    @Test
    fun `trailing zeros after EOT`() {
        val tokenizer = load()
        val ids = tokenizer.encode("a car")
        // [49406, 320, 1615, 49407, 0, 0, ...]
        assertEquals(49406, ids[0])
        assertEquals(49407, ids[3])
        for (i in 4 until 77) {
            assertEquals("Non-zero at position $i", 0, ids[i])
        }
    }
}
