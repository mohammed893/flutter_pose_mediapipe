package com.carecode.flutter_mp_pose_lm

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import androidx.camera.core.ImageProxy
import android.renderscript.*

class YuvToRgbConverter(context: Context) {
    private val rs: RenderScript = RenderScript.create(context)
    private val scriptYuvToRgb: ScriptIntrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private var yuvBits: ByteArray? = null

    fun yuvToRgb(image: Image, output: Bitmap) {
        val yuvBuffer = image.planes.toYuvByteArray(image.width, image.height)
        val input = Allocation.createSized(rs, Element.U8(rs), yuvBuffer.size)
        val outputAlloc = Allocation.createFromBitmap(rs, output)
        input.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(input)
        scriptYuvToRgb.forEach(outputAlloc)
        outputAlloc.copyTo(output)
    }

    private fun Array<Image.Plane>.toYuvByteArray(width: Int, height: Int): ByteArray {
        if (this.size < 3) {
            throw IllegalArgumentException("YUV_420_888 format requires 3 planes, but got ${this.size}")
        }
    
        val yPlane = this[0].buffer
        val uPlane = this[1].buffer
        val vPlane = this[2].buffer
    
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)
    
        yPlane.get(nv21, 0, ySize)
    
        val chromaRowStride = this[1].rowStride
        val chromaPixelStride = this[1].pixelStride
    
        var offset = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * chromaRowStride + col * chromaPixelStride
                val u = uPlane.get(uIndex)
                val v = vPlane.get(uIndex)
    
                // NV21 format requires VU ordering
                nv21[offset++] = v
                nv21[offset++] = u
            }
        }
    
        return nv21
    }
}
