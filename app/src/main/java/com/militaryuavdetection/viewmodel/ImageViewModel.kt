package com.militaryuavdetection.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.database.ImageRecordDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ImageViewModel(private val imageRecordDao: ImageRecordDao) : ViewModel() {

    val allRecords: Flow<List<ImageRecord>> = imageRecordDao.getAllRecords()

    fun insertAll(records: List<ImageRecord>) = viewModelScope.launch {
        imageRecordDao.insertAll(records)
    }

    suspend fun getLastInsertedId(): Long? {
        return imageRecordDao.getLastInsertedId()
    }

    suspend fun getRecordById(id: Long): ImageRecord? {
        return imageRecordDao.getRecordById(id)
    }
}

class ImageViewModelFactory(private val imageRecordDao: ImageRecordDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ImageViewModel(imageRecordDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}