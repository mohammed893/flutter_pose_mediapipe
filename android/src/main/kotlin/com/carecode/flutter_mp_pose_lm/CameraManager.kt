package com.carecode.flutter_mp_pose_lm

import android.app.Activity
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2Interop
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


class CameraManager(private val activity: Activity) : PoseLandmarkerHelper.LandmarkerListener, IPoseManager {

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
    private var analysisTargetSize: Size = Size(640, 480)
    private var targetFps: Int = 15
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
        minPosePresenceConfidence: Float = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
        analysisWidth: Int = 640,
        analysisHeight: Int = 480,
        fps: Int = 15
    ) {
        val newSize = Size(analysisWidth, analysisHeight)
        val sizeChanged = newSize != analysisTargetSize
        analysisTargetSize = newSize

        val fpsChanged = fps != targetFps
        targetFps = fps

        Log.d(
            "CameraManager",
            "setConfig: delegate=$delegate, model=$model, analysisSize=$analysisTargetSize, fps=$targetFps"
        )

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

        // Rebind camera to pick up the new analysis resolution/fps, if either
        // changed and the camera is already running.
        if ((sizeChanged || fpsChanged) && ::imageAnalysis.isInitialized) {
            startCamera()
        }
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
        Log.d("CameraManager", "Activity: ${activity.javaClass.simpleName}, API Level: ${android.os.Build.VERSION.SDK_INT}")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        Log.d("CameraManager", "ProcessCameraProvider.getInstance() called")
        cameraProviderFuture.addListener({
            Log.d("CameraManager", "CameraProvider ready, binding to lifecycle")
            try {
                Log.d("CameraManager", "Getting cameraProvider from future...")
                val cameraProvider = cameraProviderFuture.get()
                Log.d("CameraManager", "CameraProvider obtained successfully")

                Log.d("CameraManager", "Building resolution selectors...")
                // Preview can stay higher-res for a nice UI
                val previewResolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()

                // Analysis can go lower — this is what actually costs CPU/GPU time
                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(analysisTargetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()

                Log.d("CameraManager", "Building preview...")
                val preview = Preview.Builder()
                    .setResolutionSelector(previewResolutionSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                Log.d("CameraManager", "Preview built successfully")

                Log.d("CameraManager", "Building image analysis with FPS cap=$targetFps...")
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // Cap capture rate to reduce heat generated upstream of inference.
                // NOTE: this affects the whole capture session (preview included),
                // since preview + analysis share one session here. Not all devices
                // honor the exact range — some snap to the nearest supported one.
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(targetFps, targetFps)
                    )

                imageAnalysis = analysisBuilder.build()
                Log.d("CameraManager", "Image analysis built successfully with FPS cap=$targetFps")

                if (isAnalysisEnabled) {
                    Log.d("CameraManager", "Analysis is enabled, attaching analyzer...")
                    attachAnalyzer()
                    Log.d("CameraManager", "Analyzer attached")
                } else {
                    Log.d("CameraManager", "Analysis is disabled, skipping analyzer attachment")
                }

                Log.d("CameraManager", "Unbinding all existing camera bindings...")
                cameraProvider.unbindAll()
                Log.d("CameraManager", "All bindings unbound")

                // Try the requested lens facing first; if it fails (e.g. emulator
                // cameras report null lensFacing), fall back to any available camera.
                try {
                    Log.d("CameraManager", "Attempting to bind camera with preferred lensFacing=$currentLensFacing")
                    val preferred = CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing)
                        .build()
                    Log.d("CameraManager", "CameraSelector built successfully")
                    cameraProvider.bindToLifecycle(
                        activity as LifecycleOwner,
                        preferred,
                        preview,
                        imageAnalysis
                    )
                    previewIsMirrored = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                    Log.d("CameraManager", "Camera bound with lensFacing=$currentLensFacing, mirrored=$previewIsMirrored")
                } catch (e: Exception) {
                    Log.w("CameraManager", "Preferred lensFacing=$currentLensFacing failed: ${e.message}", e)
                    Log.w("CameraManager", "Trying fallback CameraSelector (any available)")
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
                Log.e("CameraManager", "Camera bind failed with exception", e)
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
        Log.d("CameraManager", "attachAnalyzer() called")
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            if (!isAnalysisEnabled) {
                Log.v("CameraManager", "Analysis disabled, dropping frame")
                imageProxy.close()
                return@setAnalyzer
            }

            try {
                // ----- FPS calculation -----
                val currentTime = SystemClock.elapsedRealtime()
                val deltaTime = currentTime - lastFrameTime

                if (deltaTime > 0) {
                    fps = 1000.0 / deltaTime
                }
                lastFrameTime = currentTime
                // ---------------------------

                Log.v(
                    "CameraManager",
                    "Processing frame: width=${imageProxy.width}, height=${imageProxy.height}, format=${imageProxy.format}, fps=$fps"
                )
                val helper = ensureHelper()
                Log.v("CameraManager", "PoseLandmarkerHelper obtained")

                try {
                    helper.detectLiveStream(
                        imageProxy,
                        isFrontCamera = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                    )
                    Log.v("CameraManager", "Frame sent to pose detector")
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error during analysis in detectLiveStream", e)
                    imageProxy.close()
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "Unexpected error in analyzer", e)
                try {
                    imageProxy.close()
                } catch (closeEx: Exception) {
                    Log.e("CameraManager", "Failed to close imageProxy after error", closeEx)
                }
            }
        }
        Log.d("CameraManager", "Analyzer attached successfully")
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

    override fun releaseCamera() {
        disableAnalysis()
        try {
            ProcessCameraProvider.getInstance(activity).get().unbindAll()
        } catch (e: Exception) {
            if (isLoggingEnabled) Log.e("CameraManager", "Failed to release camera provider", e)
        }
    }
}