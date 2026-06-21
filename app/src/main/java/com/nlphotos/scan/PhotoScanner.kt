package com.nlphotos.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * Thin wrapper around MediaStore that enumerates the device's images.
 * Not unit tested; the diff logic in [computeDiff] carries the testable behavior.
 */
class PhotoScanner(private val context: Context) {

    fun scan(): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateModified = cursor.getLong(dateColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                items.add(
                    MediaItem(
                        photoId = id,
                        uri = uri.toString(),
                        dateModified = dateModified,
                    ),
                )
            }
        }
        return items
    }
}
