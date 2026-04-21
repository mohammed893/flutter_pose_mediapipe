package com.carecode.flutter_mp_pose_lm

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.platform.PlatformViewRegistry
import androidx.camera.core.CameraSelector

class FlutterMpPoseLandmarkerPlugin : FlutterPlugin, EventChannel.StreamHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var eventChannel: EventChannel
    private lateinit var methodChannel: MethodChannel
    private var poseManager: IPoseManager? = null
    private var activity: Activity? = null
    private var platformViewRegistry: PlatformViewRegistry? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    private var isLoggingEnabled: Boolean = false
    private var pendingPermissionResult: MethodChannel.Result? = null
    private val CAMERA_PERMISSION_REQUEST_CODE = 9876
    private var isCameraReady = false
    private var pendingAnalysisEnable = false

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("PoseLandmarkerPlugin", "onAttachedToEngine called")

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "pose_landmarker/events")
        eventChannel.setStreamHandler(this)

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "pose_landmarker/methods")
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {

                "setConfig" -> {
                    val delegate = call.argument<Int>("delegate") ?: PoseLandmarkerHelper.DELEGATE_CPU
                    val model = call.argument<Int>("model") ?: PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_LITE
                    val minPoseDetectionConfidence = call.argument<Double>("minPoseDetectionConfidence")?.toFloat()
                        ?: PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE
                    val minPoseTrackingConfidence = call.argument<Double>("minPoseTrackingConfidence")?.toFloat()
                        ?: PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE
                    val minPosePresenceConfidence = call.argument<Double>("minPosePresenceConfidence")?.toFloat()
                        ?: PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE

                    // Only CameraManager supports setConfig — no-op on emulator
                    (poseManager as? CameraManager)?.setConfig(
                        delegate, model,
                        minPoseDetectionConfidence,
                        minPoseTrackingConfidence,
                        minPosePresenceConfidence
                    )
                    result.success(null)
                }

                "switchCamera" -> {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    // Only CameraManager supports switchCamera — no-op on emulator
                    (poseManager as? CameraManager)?.switchCamera()
                    result.success(null)
                }
                "releaseCamera" -> {
                    poseManager?.releaseCamera()
                    result.success(null)
                }

                "restoreCamera" -> {
                    startCameraIfAvailable()
                }

                "checkCameraPermission" -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        activity!!,
                        android.Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    result.success(hasPermission)
                }

                "requestCameraPermission" -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        activity!!,
                        android.Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        result.success(true)
                    } else {
                        pendingPermissionResult = result
                        ActivityCompat.requestPermissions(
                            activity!!,
                            arrayOf(android.Manifest.permission.CAMERA),
                            CAMERA_PERMISSION_REQUEST_CODE
                        )
                    }
                }

                "getCurrentCamera" -> {
                    val currentLens = (poseManager as? CameraManager)?.getCurrentCameraLens()
                        ?: CameraSelector.LENS_FACING_FRONT
                    val cameraString = if (currentLens == CameraSelector.LENS_FACING_FRONT) "front" else "back"
                    result.success(cameraString)
                }

                "isPreviewMirrored" -> {
                    val mirrored = (poseManager as? CameraManager)?.isPreviewMirrored() ?: false
                    result.success(mirrored)
                }

                "setLoggingEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    isLoggingEnabled = enabled
                    result.success(null)
                }

                "pauseAnalysis" -> {
                    poseManager?.pauseAnalysis()
                    result.success(null)
                }

                "resumeAnalysis" -> {
                    poseManager?.resumeAnalysis()
                    result.success(null)
                }

                "isEmulator" -> {
                    result.success(EmulatorDetector.isEmulator())
                }

                else -> result.notImplemented()
            }
        }

        platformViewRegistry = flutterPluginBinding.platformViewRegistry

        // Register platform view factory here (engine-scoped) — the factory
        // lazily references poseManager which is set when the activity attaches.
        platformViewRegistry?.registerViewFactory("camera_preview_view",
            object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
                override fun create(context: Context, id: Int, args: Any?): PlatformView {
                    return object : PlatformView {
                        override fun getView(): View =
                            (poseManager as? CameraManager)?.previewView
                                ?: View(context).apply {
                                    setBackgroundColor(android.graphics.Color.DKGRAY)
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                        override fun dispose() {}
                    }
                }
            }
        )
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)

        try {
            // Always use CameraManager — emulator webcam passthrough works with CameraX
            poseManager = CameraManager(activity!!)
            Log.d("PoseLandmarkerPlugin", "CameraManager created (emulator=${EmulatorDetector.isEmulator()})")
        } catch (e: Exception) {
            Log.e("PoseLandmarkerPlugin", "Failed to create CameraManager", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
            return true
        }
        return false
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        Log.d("PoseLandmarkerPlugin", "onListen called, poseManager=${poseManager?.javaClass?.simpleName}")
        poseManager?.apply {
            setEventSink(events)
            startCameraIfAvailable()
            enableAnalysis()
        }
        if (poseManager == null) {
            Log.e("PoseLandmarkerPlugin", "onListen: poseManager is NULL — camera will not start")
            events?.error("NO_MANAGER", "PoseManager not initialized", null)
        }
    }

    private fun startCameraIfAvailable() {
        (poseManager as? CameraManager)?.startCamera()
    }
    override fun onCancel(arguments: Any?) {
        poseManager?.disableAnalysis()
        (poseManager as? CameraManager)?.releaseCamera()
    }

    override fun onDetachedFromActivity() {
        poseManager?.dispose()
        poseManager = null
        activity = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        eventChannel.setStreamHandler(null)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
}