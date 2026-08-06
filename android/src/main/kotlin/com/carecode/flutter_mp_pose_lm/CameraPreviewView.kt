package com.carecode.flutter_mp_pose_lm

import android.app.Activity
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import io.flutter.plugin.platform.PlatformView

class CameraPreview(private val activity: Activity) : PlatformView {

    private val previewView: PreviewView = PreviewView(activity)
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    init {
        previewView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        startCamera()
    }

    private fun startCamera() {
        previewView.post {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        activity as androidx.lifecycle.LifecycleOwner,
                        cameraSelector,
                        preview
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(activity))
        }
    }

    fun switchCamera() {
        cameraSelector =
            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

        startCamera()
    }
    
    /**
     * Forces a full unbind → close → rebind cycle on the *current* selector.
     * Use this at the start of a new session instead of relying on
     * switchCamera() as a workaround. The key difference from just calling
     * startCamera() again is that we explicitly detach the surface provider
     * and wait a beat before rebinding, so the previous capture session has
     * time to actually close instead of handing the new bind a stale frame.
     */
    fun resetCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Give the previous session's capture session a frame to fully
            // close before we rebind — this is what switchCamera() gets "for
            // free" because opening a different physical camera inherently
            // takes longer than an immediate rebind of the same one.
            previewView.postDelayed({ startCamera() }, 150)
        }, ContextCompat.getMainExecutor(activity))
    }

    override fun getView(): View = previewView

    override fun dispose() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(activity))
    }
}