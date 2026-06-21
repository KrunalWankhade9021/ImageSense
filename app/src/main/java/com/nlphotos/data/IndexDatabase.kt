package com.nlphotos.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PhotoEntity::class], version = 1, exportSchema = false)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
}
