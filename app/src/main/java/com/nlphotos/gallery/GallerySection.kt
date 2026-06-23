package com.nlphotos.gallery

import com.nlphotos.scan.MediaItem
import java.util.Calendar

/** A run of photos sharing a human date label, newest-first. */
data class GallerySection(val label: String, val items: List<MediaItem>)

/**
 * Buckets [items] into date sections labelled "Today", "Yesterday", or
 * "<Month> <Year>". Sections and the items within them are newest-first.
 * [MediaItem.dateModified] is in seconds (MediaStore convention).
 */
fun groupByDate(items: List<MediaItem>, nowMillis: Long): List<GallerySection> {
    if (items.isEmpty()) return emptyList()

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todayStart = startOfDay(nowMillis)
    val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
    val months = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    fun label(itemMillis: Long): String {
        val day = startOfDay(itemMillis)
        return when {
            day >= todayStart -> "Today"
            day >= yesterdayStart -> "Yesterday"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = itemMillis }
                "${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
            }
        }
    }

    val sorted = items.sortedByDescending { it.dateModified }
    val sections = LinkedHashMap<String, MutableList<MediaItem>>()
    for (item in sorted) {
        val key = label(item.dateModified * 1000)
        sections.getOrPut(key) { mutableListOf() }.add(item)
    }
    return sections.map { (label, list) -> GallerySection(label, list) }
}
