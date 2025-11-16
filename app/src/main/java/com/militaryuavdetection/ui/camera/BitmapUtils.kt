package com.example.militaryuavdetection

import android.graphics.Bitmap
import android.graphics.Matrix

// File tiện ích để thêm hàm "rotate" cho Bitmap
fun Bitmap.rotate(degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}