package com.nlphotos.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDiffTest {

    private fun item(id: Long, dm: Long) =
        MediaItem(photoId = id, uri = "content://media/external/images/media/$id", dateModified = dm)

    @Test
    fun newItem_goesToEncode() {
        val current = listOf(item(1, 100))
        val indexed = emptyMap<Long, Long>()

        val diff = computeDiff(current, indexed)

        assertEquals(listOf(item(1, 100)), diff.toEncode)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun changedDateModified_goesToEncode() {
        val current = listOf(item(1, 200))
        val indexed = mapOf(1L to 100L)

        val diff = computeDiff(current, indexed)

        assertEquals(listOf(item(1, 200)), diff.toEncode)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun indexedButMissingFromCurrent_goesToDelete() {
        val current = emptyList<MediaItem>()
        val indexed = mapOf(1L to 100L)

        val diff = computeDiff(current, indexed)

        assertTrue(diff.toEncode.isEmpty())
        assertEquals(listOf(1L), diff.toDelete)
    }

    @Test
    fun unchanged_inNeitherList() {
        val current = listOf(item(1, 100))
        val indexed = mapOf(1L to 100L)

        val diff = computeDiff(current, indexed)

        assertTrue(diff.toEncode.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }
}
