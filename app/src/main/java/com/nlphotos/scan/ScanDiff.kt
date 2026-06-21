package com.nlphotos.scan

/** A photo discovered in MediaStore. */
data class MediaItem(
    val photoId: Long,
    val uri: String,
    val dateModified: Long,
)

/** Result of comparing the current device photos against the index. */
data class ScanDiff(
    val toEncode: List<MediaItem>,
    val toDelete: List<Long>,
)

/**
 * Pure diff between the current MediaStore contents and the already-indexed
 * photos (photoId -> dateModified).
 *
 * - toEncode: items new to the index, or whose dateModified changed.
 * - toDelete: indexed photoIds that are no longer present on the device.
 * - Unchanged items appear in neither list.
 */
fun computeDiff(current: List<MediaItem>, indexed: Map<Long, Long>): ScanDiff {
    val toEncode = current.filter { item ->
        val known = indexed[item.photoId]
        known == null || known != item.dateModified
    }

    val currentIds = current.mapTo(HashSet()) { it.photoId }
    val toDelete = indexed.keys.filter { it !in currentIds }

    return ScanDiff(toEncode = toEncode, toDelete = toDelete)
}
