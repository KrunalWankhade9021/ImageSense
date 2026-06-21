package com.nlphotos.data

import android.content.Context
import androidx.room.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A photo's stored embedding record. */
data class PhotoRecord(
    val photoId: Long,
    val uri: String,
    val dateModified: Long,
    val vector: FloatArray, // 512 floats, already L2-normalized
)

/** Room-backed store persisting photo embedding vectors. */
class IndexStore(private val db: IndexDatabase) {

    private val dao = db.photoDao()

    suspend fun upsert(record: PhotoRecord) {
        dao.upsert(record.toEntity())
    }

    suspend fun upsertAll(records: List<PhotoRecord>) {
        dao.upsertAll(records.map { it.toEntity() })
    }

    suspend fun delete(photoIds: List<Long>) {
        dao.deleteByIds(photoIds)
    }

    /** photoId -> dateModified, for scan diffing. */
    suspend fun allIds(): Map<Long, Long> =
        dao.selectAllIdsAndDates().associate { it.photoId to it.dateModified }

    /** All records, for loading the in-memory search buffer. */
    suspend fun allRecords(): List<PhotoRecord> =
        dao.selectAll().map { it.toRecord() }

    suspend fun count(): Int = dao.count()

    companion object {
        fun create(context: Context): IndexStore {
            val db = Room.databaseBuilder(
                context.applicationContext,
                IndexDatabase::class.java,
                "nlphotos-index",
            ).build()
            return IndexStore(db)
        }

        internal fun encodeVector(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(vector)
            return buffer.array()
        }

        internal fun decodeVector(bytes: ByteArray): FloatArray {
            val floatBuffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            val out = FloatArray(floatBuffer.remaining())
            floatBuffer.get(out)
            return out
        }
    }
}

private fun PhotoRecord.toEntity(): PhotoEntity =
    PhotoEntity(photoId, uri, dateModified, IndexStore.encodeVector(vector))

private fun PhotoEntity.toRecord(): PhotoRecord =
    PhotoRecord(photoId, uri, dateModified, IndexStore.decodeVector(vector))
