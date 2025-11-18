package com.militaryuavdetection

import android.app.Application
import com.militaryuavdetection.database.AppDatabase

class MilitaryUavApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
}
