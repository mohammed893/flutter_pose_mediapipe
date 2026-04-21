package com.carecode.flutter_mp_pose_lm

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB

class YuvToRgbConverter(context: Context) {
    private val rs: RenderScript = RenderScript.create(context)
    private val scriptYuvToRgb: ScriptIntrinsicYuvToRGB =
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var inputAlloc: Allocation? = null
    private var outputAlloc: Allocation? = null
    private var lastInputSize: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        require(!output.isRecycled) { "Output bitmap is already recycled" }
        require(output.config != Bitmap.Config.HARDWARE) {
            "Hardware bitmaps cannot be used with RenderScript — create output with Bitmap.Config.ARGB_8888"
        }
        require(output.width == image.width && output.height == image.height) {
            "Output bitmap size (${output.width}x${output.height}) must match image size (${image.width}x${image.height})"
        }

        val yuvBuffer = image.planes.toYuvByteArray(image.width, image.height)

        if (inputAlloc == null || yuvBuffer.size != lastInputSize) {
            inputAlloc?.destroy()
            inputAlloc = Allocation.createSized(rs, Element.U8(rs), yuvBuffer.size)
            lastInputSize = yuvBuffer.size
        }

        if (outputAlloc == null || output.width != lastWidth || output.height != lastHeight) {
            outputAlloc?.destroy()
            outputAlloc = Allocation.createFromBitmap(rs, output)
            lastWidth = output.width
            lastHeight = output.height
        }

        inputAlloc!!.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(inputAlloc)
        scriptYuvToRgb.forEach(outputAlloc)
        outputAlloc!!.copyTo(output)
    }

    @Synchronized
    fun destroy() {
        inputAlloc?.destroy()
        inputAlloc = null
        outputAlloc?.destroy()
        outputAlloc = null
        scriptYuvToRgb.destroy()
        rs.destroy()
    }

    private fun Array<Image.Plane>.toYuvByteArray(width: Int, height: Int): ByteArray {
        require(size >= 3) {
            "YUV_420_888 format requires 3 planes, but got $size"
        }

        val yPlane = this[0]
        val uPlane = this[1]
        val vPlane = this[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Y plane — must respect row stride; stride >= width on most devices
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // UV planes — interleaved into NV21 (V first, then U)
        // Use separate indices for U and V buffers; they share the same row/pixel
        // stride but are distinct buffers and must not be indexed interchangeably.
        val chromaRowStride = uPlane.rowStride
        val chromaPixelStride = uPlane.pixelStride

        var offset = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * chromaRowStride + col * chromaPixelStride
                nv21[offset++] = vBuffer.get(index) // V first → NV21
                nv21[offset++] = uBuffer.get(index)
            }
        }

        return nv21
    }
}