package com.carecode.flutter_mp_pose_lm

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.platform.PlatformViewRegistry

class FlutterMpPoseLandmarkerPlugin : FlutterPlugin, EventChannel.StreamHandler, ActivityAware {

    private lateinit var eventChannel: EventChannel
    private var cameraManager: CameraManager? = null
    private var activity: Activity? = null
    private var platformViewRegistry: PlatformViewRegistry? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("PoseLandmarkerPlugin", "onAttachedToEngine called")
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "pose_landmarker/events")
        eventChannel.setStreamHandler(this)
        platformViewRegistry = flutterPluginBinding.platformViewRegistry
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        cameraManager = CameraManager(activity!!).apply {
            startCamera()
        }
        
        // Register platform view with shared PreviewView
        platformViewRegistry?.registerViewFactory("camera_preview_view", 
            object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
                override fun create(context: Context, id: Int, args: Any?): PlatformView {
                    return object : PlatformView {
                        override fun getView() = cameraManager?.previewView ?: run {
                            View(context).apply { 
                                layoutParams = FrameLayout.LayoutParams(1, 1) // Placeholder
                            }
                        }
                        override fun dispose() {}
                    }
                }
            }
        )
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        cameraManager?.apply {
            setEventSink(events)
            enableAnalysis()
        }
    }

    override fun onCancel(arguments: Any?) {
        cameraManager?.disableAnalysis()
    }

    override fun onDetachedFromActivity() {
        cameraManager?.dispose()
        cameraManager = null
        activity = null
    }

    // Other lifecycle methods (keep existing implementations)
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        eventChannel.setStreamHandler(null)
    }
    
    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
}