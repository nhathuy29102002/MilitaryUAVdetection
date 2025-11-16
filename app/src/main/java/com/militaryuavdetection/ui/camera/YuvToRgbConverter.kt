package com.militaryuavdetection.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.*
import java.nio.ByteBuffer

// Lớp này dùng RenderScript (cách nhanh nhất) để chuyển đổi YUV -> RGB
// Lưu ý: RenderScript đã bị "deprecated" nhưng vẫn là cách nhanh nhất trên nhiều thiết bị
class YuvToRgbConverter(context: Context) {
    private val rs: RenderScript = RenderScript.create(context)
    private val script: ScriptIntrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var yBuffer: ByteBuffer? = null
    private var uBuffer: ByteBuffer? = null
    private var vBuffer: ByteBuffer? = null
    private var yAllocation: Allocation? = null
    private var uAllocation: Allocation? = null
    private var vAllocation: Allocation? = null

    @SuppressLint("UnsafeOptInUsageError")
    fun yuvToRgb(image: Image, output: Bitmap) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Chỉ hỗ trợ YUV_420_888")
        }

        val planes = image.planes
        val width = image.width
        val height = image.height

        // Cấp phát Allocation (chỉ 1 lần nếu kích thước không đổi)
        if (yAllocation == null || yAllocation?.type?.x != width || yAllocation?.type?.y != height) {
            yAllocation = Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width, height))
            uAllocation = Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width / 2, height / 2))
            vAllocation = Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width / 2, height / 2))
        }

        // Copy dữ liệu từ planes sang buffers
        yBuffer = planes[0].buffer
        uBuffer = planes[1].buffer
        vBuffer = planes[2].buffer

        yAllocation!!.copyFrom(getBytes(yBuffer, planes[0].rowStride, height))
        uAllocation!!.copyFrom(getBytes(uBuffer, planes[1].rowStride, height / 2))
        vAllocation!!.copyFrom(getBytes(vBuffer, planes[2].rowStride, height / 2))

        // Chạy script
        script.setInput(yAllocation)
        // script.setInput(uAllocation) // RenderScript YUV_420_888 nội bộ xử lý UV
        // script.setInput(vAllocation)

        val outAllocation = Allocation.createFromBitmap(rs, output)
        script.forEach(outAllocation) // Chạy chuyển đổi
        outAllocation.copyTo(output) // Copy kết quả vào bitmap
    }

    // Helper để xử lý row stride
    private fun getBytes(buffer: ByteBuffer?, rowStride: Int, height: Int): ByteArray {
        buffer!!.rewind()
        val size = rowStride * height
        // Nếu buffer là direct, copy nhanh hơn
        if (buffer.isDirect) {
            val bytes = ByteArray(size)
            buffer.get(bytes)
            return bytes
        }
        return buffer.array()
    }
}