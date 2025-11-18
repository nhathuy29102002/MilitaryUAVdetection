package com.militaryuavdetection.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<ImageRecord>)

    @Query("SELECT * FROM image_records ORDER BY id DESC")
    fun getAllRecords(): Flow<List<ImageRecord>>

    @Query("SELECT * FROM image_records WHERE id = :id")
    suspend fun getRecordById(id: Long): ImageRecord?

    @Query("SELECT * FROM image_records WHERE uri = :uri LIMIT 1")
    suspend fun getRecordByUri(uri: String): ImageRecord?

    @Query("SELECT id FROM image_records ORDER BY id DESC LIMIT 1")
    suspend fun getLastInsertedId(): Long?

    @Delete
    suspend fun delete(record: ImageRecord)

    @Query("DELETE FROM image_records")
    suspend fun clearAll()
}
