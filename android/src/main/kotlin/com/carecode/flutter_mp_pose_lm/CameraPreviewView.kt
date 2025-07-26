package com.carecode.flutter_mp_pose_lm

import android.content.Context
import android.view.View
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.flutter.plugin.platform.PlatformView

class CameraPreview(context: Context) : PlatformView, LifecycleOwner {
    private val previewView: PreviewView = PreviewView(context)
    private val lifecycleRegistry = LifecycleRegistry(this)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, // this is now a valid LifecycleOwner
                    cameraSelector,
                    preview
                )
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override fun getView(): View = previewView
    override fun dispose() {}
}