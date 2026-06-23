package com.nlphotos.gallery

import com.nlphotos.scan.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class GallerySectionTest {
    private fun item(id: Long, sec: Long) = MediaItem(id, "uri$id", sec)

    // Fixed "now": 2026-06-23 12:00:00 local
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JUNE, 23, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun secsAt(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance().apply {
            set(y, m, d, 9, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 1000
    }

    @Test fun groupsTodayYesterdayAndMonth() {
        val items = listOf(
            item(1, secsAt(2026, Calendar.JUNE, 23)),  // Today
            item(2, secsAt(2026, Calendar.JUNE, 22)),  // Yesterday
            item(3, secsAt(2026, Calendar.JUNE, 22)),  // Yesterday
            item(4, secsAt(2024, Calendar.JUNE, 1)),   // June 2024
        )
        val sections = groupByDate(items, now)
        assertEquals(listOf("Today", "Yesterday", "June 2024"), sections.map { it.label })
        assertEquals(listOf(2L, 3L), sections[1].items.map { it.photoId })
    }

    @Test fun emptyInputYieldsNoSections() {
        assertEquals(emptyList<GallerySection>(), groupByDate(emptyList(), now))
    }

    @Test fun sectionsAreNewestFirstAndItemsNewestFirst() {
        val items = listOf(
            item(1, secsAt(2024, Calendar.JUNE, 1)),
            item(2, secsAt(2026, Calendar.JUNE, 23)),
        )
        val sections = groupByDate(items, now)
        assertEquals("Today", sections.first().label)
        assertEquals(2L, sections.first().items.first().photoId)
    }
}
