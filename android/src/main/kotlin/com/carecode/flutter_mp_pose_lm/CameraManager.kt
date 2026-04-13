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


class CameraManager(private val activity: Activity) : PoseLandmarkerHelper.LandmarkerListener, IPoseManager{

    data class Landmark(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float = 0f,
        val presence: Float = 0f
    )

    data class WorldLandmark(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float = 0f,
        val presence: Float = 0f
    )

    val previewView = PreviewView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // COMPATIBLE uses TextureView, which renders correctly inside Flutter's AndroidView
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var currentLensFacing: Int = CameraSelector.LENS_FACING_FRONT
    // True when the camera was bound with a known front-facing selector,
    // meaning PreviewView mirrors the feed.
    private var previewIsMirrored: Boolean = false
    private val eventSink = AtomicReference<EventChannel.EventSink?>(null)
    private lateinit var imageAnalysis: ImageAnalysis
    private var isAnalysisEnabled = false
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    // Logging toggle variable
    var isLoggingEnabled: Boolean = false

    // FPS counter variables
    private var lastFrameTime = SystemClock.elapsedRealtime()
    private var fps = 0.0

    // Lazily initialized — do NOT create in constructor (GPU delegate crashes on emulators)
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    private fun ensureHelper(): PoseLandmarkerHelper {
        var helper = poseLandmarkerHelper
        if (helper == null) {
            Log.d("CameraManager", "Creating PoseLandmarkerHelper (CPU delegate)")
            helper = PoseLandmarkerHelper(
                context = activity,
                runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener = this,
                currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
            )
            poseLandmarkerHelper = helper
        }
        return helper
    }

    fun setConfig(
        delegate: Int,
        model: Int,
        minPoseDetectionConfidence: Float = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
        minPoseTrackingConfidence: Float = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
        minPosePresenceConfidence: Float = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE
    ) {
        Log.d("CameraManager", "setConfig: delegate=$delegate, model=$model")
        // Dispose old helper if it exists
        poseLandmarkerHelper?.clearPoseLandmarker()

        // Create a new one with updated config
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = activity,
            runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener = this,
            currentDelegate = delegate,
            currentModel = model,
            minPoseDetectionConfidence = minPoseDetectionConfidence,
            minPoseTrackingConfidence = minPoseTrackingConfidence,
            minPosePresenceConfidence = minPosePresenceConfidence
        )
        Log.d("CameraManager", "setConfig: PoseLandmarkerHelper created successfully")
    }

    fun switchCamera() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera() // restart camera pipeline with new lens
    }

    fun getCurrentCameraLens(): Int {
        return currentLensFacing
    }

    fun isPreviewMirrored(): Boolean {
        return previewIsMirrored
    }

    fun startCamera() {
        Log.d("CameraManager", "startCamera() called, lensFacing=$currentLensFacing, analysisEnabled=$isAnalysisEnabled")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            Log.d("CameraManager", "CameraProvider ready, binding to lifecycle")
            try {
                val cameraProvider = cameraProviderFuture.get()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                if (isAnalysisEnabled) {
                    attachAnalyzer()
                }

                cameraProvider.unbindAll()

                // Try the requested lens facing first; if it fails (e.g. emulator
                // cameras report null lensFacing), fall back to any available camera.
                try {
                    val preferred = CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing)
                        .build()
                    cameraProvider.bindToLifecycle(
                        activity as LifecycleOwner,
                        preferred,
                        preview,
                        imageAnalysis
                    )
                    previewIsMirrored = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                    Log.d("CameraManager", "Camera bound with lensFacing=$currentLensFacing, mirrored=$previewIsMirrored")
                } catch (e: Exception) {
                    Log.w("CameraManager", "Preferred lensFacing=$currentLensFacing failed: ${e.message}")
                    val fallback = CameraSelector.Builder().build()
                    cameraProvider.bindToLifecycle(
                        activity as LifecycleOwner,
                        fallback,
                        preview,
                        imageAnalysis
                    )
                    previewIsMirrored = false  // unknown facing → no mirror
                    Log.d("CameraManager", "Camera bound with fallback (any available), mirrored=false")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "Camera bind failed", e)
                activity.runOnUiThread {
                    eventSink.get()?.error("CAMERA_ERROR", "Failed to start camera: ${e.message}", null)
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    override fun setEventSink(sink: EventChannel.EventSink?) {
        eventSink.set(sink)
    }

    override fun enableAnalysis() {
        isAnalysisEnabled = true
        // If camera is already running, attach the analyzer now.
        // Otherwise, startCamera() will pick up the flag.
        if (::imageAnalysis.isInitialized) {
            attachAnalyzer()
        }
    }

    override fun disableAnalysis() {
        isAnalysisEnabled = false
        if (::imageAnalysis.isInitialized) {
            imageAnalysis.clearAnalyzer()
        }
    }

    private fun attachAnalyzer() {
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            if (!isAnalysisEnabled) {
                imageProxy.close()
                return@setAnalyzer
            }

            // ----- FPS calculation -----
            val currentTime = SystemClock.elapsedRealtime()
            val deltaTime = currentTime - lastFrameTime
            if (deltaTime > 0) {
                fps = 1000.0 / deltaTime
            }
            lastFrameTime = currentTime
            // ---------------------------

            val helper = poseLandmarkerHelper
            if (helper == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            try {
                helper.detectLiveStream(
                    imageProxy,
                    isFrontCamera = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                )
            } catch (e: Exception) {
                Log.e("CameraManager", "Error during analysis", e)
                imageProxy.close()
            }
        }
    }

    // -----------------------------
    // Pause pose detection without stopping the camera
    override fun pauseAnalysis() {
        isAnalysisEnabled = false
        if (isLoggingEnabled) Log.d("CameraManager", "Pose analysis paused")
    }

    // Resume pose detection while keeping the camera live
    override fun resumeAnalysis() {
        isAnalysisEnabled = true
        if (isLoggingEnabled) Log.d("CameraManager", "Pose analysis resumed")
    }
    // -----------------------------

    override fun dispose() {
        disableAnalysis()
        poseLandmarkerHelper?.clearPoseLandmarker()
        executor.shutdown()
        try {
            ProcessCameraProvider.getInstance(activity).get().unbindAll()
        } catch (e: Exception) {
            if (isLoggingEnabled) Log.e("CameraManager", "Failed to unbind camera provider", e)
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        try {
            val poseLandmarkerResult = resultBundle.results.firstOrNull()
            if (poseLandmarkerResult != null) {
                val landmarks = poseLandmarkerResult.landmarks().flatMap { landmarkList ->
                landmarkList.map { landmark ->
                    // Use presence as the primary confidence metric (0.0-1.0)
                    // Default to 0.0 if not available, not 1.0
                    val pres = try { landmark.presence().orElse(0.0f) } catch (_: Exception) { 0.0f }
                    val vis = try { landmark.visibility().orElse(0.0f) } catch (_: Exception) { 0.0f }
                    Landmark(
                        x = landmark.x(),
                        y = landmark.y(),
                        z = landmark.z(),
                        visibility = vis,
                        presence = pres
                    )
                }
            }

            val worldLandmarks = poseLandmarkerResult.worldLandmarks().flatMap { landmarkList ->
                landmarkList.map { landmark ->
                    val pres = try { landmark.presence().orElse(0.0f) } catch (_: Exception) { 0.0f }
                    val vis = try { landmark.visibility().orElse(0.0f) } catch (_: Exception) { 0.0f }
                    WorldLandmark(
                        x = landmark.x(),
                        y = landmark.y(),
                        z = landmark.z(),
                        visibility = vis,
                        presence = pres
                    )
                }
            }

            val resultMap = mapOf(
                "timestampMs" to SystemClock.uptimeMillis(),
                "landmarks" to landmarks,
                "worldLandmarks" to worldLandmarks,
                "fps" to fps
            )

            val json = gson.toJson(resultMap)
            activity.runOnUiThread {
                eventSink.get()?.success(json)
            }
        }
        } catch (e: Exception) {
            Log.e("CameraManager", "onResults crashed", e)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("CameraManager", "onError: $error (code=$errorCode)")
        activity.runOnUiThread {
            eventSink.get()?.error("POSE_ERROR", error, mapOf("code" to errorCode))
        }
    }
    override  fun releaseCamera(){
        disableAnalysis();
        try{
            ProcessCameraProvider.getInstance(activity).get().unbindAll()
        }catch (e:Exception){
            if(isLoggingEnabled) Log.e("CameraManager", "Failed to release camera provider", e)
        }
    }
}
