package com.nlphotos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val photoId: Long,
    val uri: String,
    val dateModified: Long,
    val vector: ByteArray,
)
