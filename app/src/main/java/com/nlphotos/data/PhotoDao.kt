package com.nlphotos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE photoId IN (:photoIds)")
    suspend fun deleteByIds(photoIds: List<Long>)

    @Query("SELECT photoId, dateModified FROM photos")
    suspend fun selectAllIdsAndDates(): List<IdAndDate>

    @Query("SELECT * FROM photos")
    suspend fun selectAll(): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int
}

data class IdAndDate(
    val photoId: Long,
    val dateModified: Long,
)
