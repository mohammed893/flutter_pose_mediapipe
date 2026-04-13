import Flutter
import UIKit
import AVFoundation

/// Flutter plugin for MediaPipe Pose Landmarker on iOS.
/// Mirrors the Android `FlutterMpPoseLandmarkerPlugin.kt`.
///
/// - Registers EventChannel `pose_landmarker/events` for streaming pose results
/// - Registers MethodChannel `pose_landmarker/methods` for control commands
/// - Registers platform view `camera_preview_view` for native camera preview
public class FlutterMpPoseLandmarkerPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    private var eventChannel: FlutterEventChannel?
    private var methodChannel: FlutterMethodChannel?
    private var eventSink: FlutterEventSink?

    private var cameraManager: CameraManager?
    private var registrar: FlutterPluginRegistrar?

    // MARK: - FlutterPlugin Registration

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterMpPoseLandmarkerPlugin()
        instance.registrar = registrar

        // Event channel for streaming pose results
        let eventChannel = FlutterEventChannel(
            name: "pose_landmarker/events",
            binaryMessenger: registrar.messenger()
        )
        eventChannel.setStreamHandler(instance)
        instance.eventChannel = eventChannel

        // Method channel for control commands
        let methodChannel = FlutterMethodChannel(
            name: "pose_landmarker/methods",
            binaryMessenger: registrar.messenger()
        )
        methodChannel.setMethodCallHandler(instance.handle)
        instance.methodChannel = methodChannel

        // Create camera manager
        let cameraManager = CameraManager()
        instance.cameraManager = cameraManager

        // Register platform view for native camera preview
        let factory = NativeCameraViewFactory(cameraManager: cameraManager)
        registrar.register(factory, withId: "camera_preview_view")
    }

    // MARK: - MethodChannel Handler

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any]

        switch call.method {
        case "setConfig":
            let delegate = args?["delegate"] as? Int ?? PoseLandmarkerHelper.delegateCPU
            let model = args?["model"] as? Int ?? PoseLandmarkerHelper.modelLite
            let minDetection = (args?["minPoseDetectionConfidence"] as? Double)
                .map { Float($0) } ?? PoseLandmarkerHelper.defaultPoseDetectionConfidence
            let minTracking = (args?["minPoseTrackingConfidence"] as? Double)
                .map { Float($0) } ?? PoseLandmarkerHelper.defaultPoseTrackingConfidence
            let minPresence = (args?["minPosePresenceConfidence"] as? Double)
                .map { Float($0) } ?? PoseLandmarkerHelper.defaultPosePresenceConfidence

            cameraManager?.setConfig(
                delegate: delegate,
                model: model,
                minPoseDetectionConfidence: minDetection,
                minPoseTrackingConfidence: minTracking,
                minPosePresenceConfidence: minPresence
            )
            result(nil)

        case "switchCamera":
            cameraManager?.switchCamera()
            result(nil)

        case "getCurrentCamera":
            let position = cameraManager?.getCurrentCameraPosition() ?? .front
            result(position == .front ? "front" : "back")

        case "setLoggingEnabled":
            let enabled = args?["enabled"] as? Bool ?? false
            cameraManager?.isLoggingEnabled = enabled
            result(nil)

        case "pauseAnalysis":
            cameraManager?.pauseAnalysis()
            result(nil)

        case "resumeAnalysis":
            cameraManager?.resumeAnalysis()
            result(nil)

        case "releaseCamera":
            cameraManager?.releaseCamera()
            result(nil)

        case "restoreCamera":
            cameraManager?.startCamera()
            result(nil)

        case "checkCameraPermission":
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            result(status == .authorized)

        case "requestCameraPermission":
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    result(granted)
                }
            }

        case "isPreviewMirrored":
            result(cameraManager?.previewIsMirrored ?? false)

        case "isEmulator":
            #if targetEnvironment(simulator)
            result(true)
            #else
            result(false)
            #endif

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - FlutterStreamHandler (EventChannel)

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        print("[Plugin] onListen — cameraManager: \(cameraManager != nil)")

        // Wire camera manager results to event sink
        cameraManager?.onResults = { [weak self] json in
            self?.eventSink?(json)
        }
        cameraManager?.onError = { [weak self] error, code in
            self?.eventSink?(FlutterError(code: "POSE_ERROR", message: error, details: ["code": code]))
        }

        // Start camera and analysis (lazy start — matching Android behavior)
        cameraManager?.enableAnalysis()
        cameraManager?.startCamera()

        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        cameraManager?.disableAnalysis()
        cameraManager?.releaseCamera()
        eventSink = nil
        return nil
    }
}
