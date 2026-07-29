package com.carecode.flutter_mp_pose_lm

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.util.Log

class YuvToRgbConverter(context: Context) {
    companion object {
        private const val TAG = "YuvToRgbConverter"
    }

    private val rs: RenderScript = RenderScript.create(context)
    private val scriptYuvToRgb: ScriptIntrinsicYuvToRGB =
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var inputAlloc: Allocation? = null
    private var outputAlloc: Allocation? = null
    private var lastInputSize: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    init {
        Log.d(TAG, "YuvToRgbConverter initialized, RenderScript API Level: ${android.os.Build.VERSION.SDK_INT}")
    }

    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        Log.v(TAG, "yuvToRgb() called - image: ${image.width}x${image.height}, output: ${output.width}x${output.height}")
        
        require(!output.isRecycled) { "Output bitmap is already recycled" }
        require(output.config != Bitmap.Config.HARDWARE) {
            "Hardware bitmaps cannot be used with RenderScript — create output with Bitmap.Config.ARGB_8888"
        }
        require(output.width == image.width && output.height == image.height) {
            "Output bitmap size (${output.width}x${output.height}) must match image size (${image.width}x${image.height})"
        }

        try {
            Log.v(TAG, "Converting image planes to YUV byte array...")
            val yuvBuffer = image.planes.toYuvByteArray(image.width, image.height)
            Log.v(TAG, "YUV buffer created, size: ${yuvBuffer.size} bytes")

            if (inputAlloc == null || yuvBuffer.size != lastInputSize) {
                Log.d(TAG, "Creating new input allocation, size: ${yuvBuffer.size}")
                inputAlloc?.destroy()
                inputAlloc = Allocation.createSized(rs, Element.U8(rs), yuvBuffer.size)
                lastInputSize = yuvBuffer.size
                Log.d(TAG, "Input allocation created")
            }

            if (outputAlloc == null || output.width != lastWidth || output.height != lastHeight) {
                Log.d(TAG, "Creating new output allocation, size: ${output.width}x${output.height}")
                outputAlloc?.destroy()
                outputAlloc = Allocation.createFromBitmap(rs, output)
                lastWidth = output.width
                lastHeight = output.height
                Log.d(TAG, "Output allocation created")
            }

            Log.v(TAG, "Copying YUV data to input allocation...")
            inputAlloc!!.copyFrom(yuvBuffer)
            Log.v(TAG, "Setting input for script...")
            scriptYuvToRgb.setInput(inputAlloc)
            Log.v(TAG, "Running script intrinsic...")
            scriptYuvToRgb.forEach(outputAlloc)
            Log.v(TAG, "Copying output allocation to bitmap...")
            outputAlloc!!.copyTo(output)
            Log.v(TAG, "YUV to RGB conversion completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in yuvToRgb - Exception type: ${e.javaClass.simpleName}, Message: ${e.message}", e)
            throw e
        }
    }

    @Synchronized
    fun destroy() {
        Log.d(TAG, "Destroying YuvToRgbConverter resources...")
        try {
            inputAlloc?.destroy()
            inputAlloc = null
            outputAlloc?.destroy()
            outputAlloc = null
            scriptYuvToRgb.destroy()
            rs.destroy()
            Log.d(TAG, "Destroy completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during destroy", e)
        }
    }

    private fun Array<Image.Plane>.toYuvByteArray(width: Int, height: Int): ByteArray {
        Log.v(TAG, "toYuvByteArray() called - size: ${width}x${height}, planes: $size")
        
        require(size >= 3) {
            "YUV_420_888 format requires 3 planes, but got $size"
        }

        val yPlane = this[0]
        val uPlane = this[1]
        val vPlane = this[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        Log.v(TAG, "Y plane - rowStride: ${yPlane.rowStride}, pixelStride: ${yPlane.pixelStride}")
        Log.v(TAG, "U plane - rowStride: ${uPlane.rowStride}, pixelStride: ${uPlane.pixelStride}")
        Log.v(TAG, "V plane - rowStride: ${vPlane.rowStride}, pixelStride: ${vPlane.pixelStride}")

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Y plane — must respect row stride; stride >= width on most devices
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            Log.v(TAG, "Y plane stride matches width, copying directly...")
            yBuffer.get(nv21, 0, ySize)
        } else {
            Log.v(TAG, "Y plane stride does not match width (${yRowStride} vs $width), copying row by row...")
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }
        Log.v(TAG, "Y plane copied, size: $ySize bytes")

        // UV planes — interleaved into NV21 (V first, then U)
        // Use separate indices for U and V buffers; they share the same row/pixel
        // stride but are distinct buffers and must not be indexed interchangeably.
        val chromaRowStride = uPlane.rowStride
        val chromaPixelStride = uPlane.pixelStride

        Log.v(TAG, "Processing UV planes - chromaRowStride: $chromaRowStride, chromaPixelStride: $chromaPixelStride")
        var offset = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * chromaRowStride + col * chromaPixelStride
                nv21[offset++] = vBuffer.get(index) // V first → NV21
                nv21[offset++] = uBuffer.get(index)
            }
        }
        Log.v(TAG, "UV planes copied, size: $uvSize bytes")

        Log.v(TAG, "YUV byte array creation completed, total size: ${nv21.size}")
        return nv21
    }
}