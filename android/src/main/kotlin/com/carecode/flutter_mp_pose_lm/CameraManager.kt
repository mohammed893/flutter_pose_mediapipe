package com.carecode.flutter_mp_pose_lm

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CameraManager(private val activity: Activity) : PoseLandmarkerHelper.LandmarkerListener {

    data class Landmark(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Double = 1.0
    )

    val previewView = PreviewView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private val eventSink = AtomicReference<EventChannel.EventSink?>(null)
    private lateinit var imageAnalysis: ImageAnalysis
    private var isAnalysisEnabled = false
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    private var poseLandmarkerHelper = PoseLandmarkerHelper(
        context = activity,
        runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM,
        poseLandmarkerHelperListener = this
    )
    fun setConfig(delegate: Int, model: Int) {
        // Dispose the old helper
        poseLandmarkerHelper.clearPoseLandmarker()
    
        // Create a new one with updated config
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = activity,
            runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener = this,
            currentDelegate = delegate,
            currentModel = model
        )
    }

    fun switchCamera() {
    currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
    startCamera() // restart camera pipeline with new lens
}

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                
                val cameraProvider = cameraProviderFuture.get()

                

                // Create a resolution selector targeting 640x480 with 4:3 aspect ratio
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_NONE
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_NONE
                        )
                    )
                    .build()

                // Configure Preview
                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Configure ImageAnalysis
                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    if (isAnalysisEnabled) {
                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            if (!isAnalysisEnabled) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            try {
                                poseLandmarkerHelper.detectLiveStream(
                                    imageProxy,
                                    isFrontCamera = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                                )
                            } catch (e: Exception) {
                                Log.e("CameraManager", "Error during analysis", e)
                                imageProxy.close()
                            }
                        }
                    }
                    
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(currentLensFacing)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraManager", "Camera bind failed", e)
                activity.runOnUiThread {
                    eventSink.get()?.error("CAMERA_ERROR", "Failed to start camera: ${e.message}", null)
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun setEventSink(sink: EventChannel.EventSink?) {
        eventSink.set(sink)
    }

    fun enableAnalysis() {
        isAnalysisEnabled = true
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            if (!isAnalysisEnabled) {
                imageProxy.close()
                return@setAnalyzer
            }
    
            try {
                poseLandmarkerHelper.detectLiveStream(
                    imageProxy,
                    isFrontCamera = false
                )
            } catch (e: Exception) {
                Log.e("CameraManager", "Error during analysis", e)
                imageProxy.close() // Only in case of crash
            }
        }
    }

    fun disableAnalysis() {
        isAnalysisEnabled = false
        imageAnalysis.clearAnalyzer()
    }

    fun dispose() {
        disableAnalysis()
        poseLandmarkerHelper.clearPoseLandmarker()
        executor.shutdown()
        try {
            ProcessCameraProvider.getInstance(activity).get().unbindAll()
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to unbind camera provider", e)
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val poseLandmarkerResult = resultBundle.results.firstOrNull()
        if (poseLandmarkerResult != null) {
            val landmarks = poseLandmarkerResult.landmarks().flatMap { landmarkList ->
                landmarkList.map { landmark ->
                    Landmark(
                        x = landmark.x(),
                        y = landmark.y(),
                        z = landmark.z()
                    )
                }
            }

            val resultMap = mapOf(
                "timestampMs" to SystemClock.uptimeMillis(),
                "landmarks" to landmarks
            )

            val json = gson.toJson(resultMap)
            activity.runOnUiThread {
                eventSink.get()?.success(json)
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity.runOnUiThread {
            eventSink.get()?.error("POSE_ERROR", error, mapOf("code" to errorCode))
        }
    }
}
