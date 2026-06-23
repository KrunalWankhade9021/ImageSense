package com.nlphotos.search

import com.nlphotos.data.PhotoRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorBufferLookupTest {
    @Test fun returnsStoredVectorForIndexedPhoto() {
        val buf = VectorBuffer()
        buf.load(listOf(
            PhotoRecord(1, "u1", 0, floatArrayOf(1f, 0f)),
            PhotoRecord(2, "u2", 0, floatArrayOf(0f, 1f)),
        ))
        assertArrayEquals(floatArrayOf(0f, 1f), buf.vectorFor(2), 0f)
    }

    @Test fun returnsNullForUnindexedPhoto() {
        val buf = VectorBuffer()
        buf.load(listOf(PhotoRecord(1, "u1", 0, floatArrayOf(1f, 0f))))
        assertNull(buf.vectorFor(999))
    }
}
