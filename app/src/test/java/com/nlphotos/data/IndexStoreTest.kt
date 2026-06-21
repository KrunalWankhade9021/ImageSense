package com.nlphotos.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexStoreTest {

    private lateinit var db: IndexDatabase
    private lateinit var store: IndexStore

    private fun vec(seed: Float) = FloatArray(512) { i -> seed + i * 0.001f }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IndexDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = IndexStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allIds_returnsBothWithCorrectDateModified() = runBlocking {
        store.upsertAll(
            listOf(
                PhotoRecord(1L, "uri://1", 100L, vec(0.1f)),
                PhotoRecord(2L, "uri://2", 200L, vec(0.2f)),
            ),
        )
        val ids = store.allIds()
        assertEquals(2, ids.size)
        assertEquals(100L, ids[1L])
        assertEquals(200L, ids[2L])
    }

    @Test
    fun allRecords_roundTripsVectorExactly() = runBlocking {
        val original = vec(0.42f)
        store.upsert(PhotoRecord(7L, "uri://7", 50L, original))
        val records = store.allRecords()
        assertEquals(1, records.size)
        val r = records.first()
        assertEquals(7L, r.photoId)
        assertEquals("uri://7", r.uri)
        assertEquals(50L, r.dateModified)
        assertEquals(512, r.vector.size)
        assertArrayEquals(original, r.vector, 0.0f)
    }

    @Test
    fun delete_removesOne_andCountReflects() = runBlocking {
        store.upsertAll(
            listOf(
                PhotoRecord(1L, "uri://1", 100L, vec(0.1f)),
                PhotoRecord(2L, "uri://2", 200L, vec(0.2f)),
            ),
        )
        assertEquals(2, store.count())
        store.delete(listOf(1L))
        assertEquals(1, store.count())
        assertNull(store.allIds()[1L])
        assertEquals(200L, store.allIds()[2L])
    }

    @Test
    fun upsert_existingId_replaces() = runBlocking {
        store.upsert(PhotoRecord(3L, "uri://3", 300L, vec(0.3f)))
        val updatedVec = vec(0.9f)
        store.upsert(PhotoRecord(3L, "uri://3b", 999L, updatedVec))
        assertEquals(1, store.count())
        val r = store.allRecords().first()
        assertEquals(999L, r.dateModified)
        assertEquals("uri://3b", r.uri)
        assertArrayEquals(updatedVec, r.vector, 0.0f)
    }
}
