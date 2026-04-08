import Foundation
import CoreMedia
import MediaPipeTasksVision

/// Wraps MediaPipe Pose Landmarker configuration and async detection.
/// Mirrors the Android `PoseLandmarkerHelper.kt`.
class PoseLandmarkerHelper: NSObject {
    // MARK: - Constants (matching Android companion object)
    static let delegateCPU = 0
    static let delegateGPU = 1
    static let modelFull = 0
    static let modelLite = 1
    static let modelHeavy = 2

    static let defaultPoseDetectionConfidence: Float = 0.5
    static let defaultPoseTrackingConfidence: Float = 0.5
    static let defaultPosePresenceConfidence: Float = 0.5

    // MARK: - Properties
    private var poseLandmarker: PoseLandmarker?
    private let context: Bundle

    var minPoseDetectionConfidence: Float
    var minPoseTrackingConfidence: Float
    var minPosePresenceConfidence: Float
    var currentModel: Int
    var currentDelegate: Int

    weak var delegate: PoseLandmarkerHelperDelegate?

    // MARK: - Init

    init(
        minPoseDetectionConfidence: Float = defaultPoseDetectionConfidence,
        minPoseTrackingConfidence: Float = defaultPoseTrackingConfidence,
        minPosePresenceConfidence: Float = defaultPosePresenceConfidence,
        currentModel: Int = modelLite,
        currentDelegate: Int = delegateGPU,
        delegate: PoseLandmarkerHelperDelegate? = nil
    ) {
        super.init()
        self.minPoseDetectionConfidence = minPoseDetectionConfidence
        self.minPoseTrackingConfidence = minPoseTrackingConfidence
        self.minPosePresenceConfidence = minPosePresenceConfidence
        self.currentModel = currentModel
        self.currentDelegate = currentDelegate
        self.delegate = delegate

        // Find the bundle containing our model assets
        self.context = PoseLandmarkerHelper.modelBundle()

        // Deferred setup — don't init the model here.
        // Call setupPoseLandmarker() explicitly (e.g. from setConfig).
    }

    // MARK: - Setup

    func setupPoseLandmarker() {
        let modelName: String
        switch currentModel {
        case PoseLandmarkerHelper.modelFull:
            modelName = "pose_landmarker_full"
        case PoseLandmarkerHelper.modelLite:
            modelName = "pose_landmarker_lite"
        case PoseLandmarkerHelper.modelHeavy:
            modelName = "pose_landmarker_heavy"
        default:
            modelName = "pose_landmarker_lite"
        }

        guard let modelPath = context.path(forResource: modelName, ofType: "task") else {
            delegate?.poseLandmarkerHelper(self, didFailWithError: "Model file '\(modelName).task' not found in bundle")
            return
        }

        do {
            let options = PoseLandmarkerOptions()
            options.baseOptions.modelAssetPath = modelPath
            options.baseOptions.delegate = currentDelegate == PoseLandmarkerHelper.delegateGPU
                ? Delegate.GPU
                : Delegate.CPU
            options.runningMode = .liveStream
            options.minPoseDetectionConfidence = minPoseDetectionConfidence
            options.minTrackingConfidence = minPoseTrackingConfidence
            options.minPosePresenceConfidence = minPosePresenceConfidence
            options.numPoses = 1
            options.poseLandmarkerLiveStreamDelegate = self

            poseLandmarker = try PoseLandmarker(options: options)
        } catch {
            // GPU delegate failed — auto-fallback to CPU (matching Android behavior)
            if currentDelegate == PoseLandmarkerHelper.delegateGPU {
                print("[PoseLandmarkerHelper] GPU delegate failed, falling back to CPU: \(error.localizedDescription)")
                currentDelegate = PoseLandmarkerHelper.delegateCPU
                do {
                    let cpuOptions = PoseLandmarkerOptions()
                    cpuOptions.baseOptions.modelAssetPath = modelPath
                    cpuOptions.baseOptions.delegate = Delegate.CPU
                    cpuOptions.runningMode = .liveStream
                    cpuOptions.minPoseDetectionConfidence = minPoseDetectionConfidence
                    cpuOptions.minTrackingConfidence = minPoseTrackingConfidence
                    cpuOptions.minPosePresenceConfidence = minPosePresenceConfidence
                    cpuOptions.numPoses = 1
                    cpuOptions.poseLandmarkerLiveStreamDelegate = self
                    poseLandmarker = try PoseLandmarker(options: cpuOptions)
                    print("[PoseLandmarkerHelper] Successfully fell back to CPU delegate")
                } catch {
                    delegate?.poseLandmarkerHelper(self, didFailWithError: "Failed to initialize with both GPU and CPU: \(error.localizedDescription)")
                }
            } else {
                delegate?.poseLandmarkerHelper(self, didFailWithError: "Failed to initialize PoseLandmarker: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Detection

    /// Detect pose asynchronously from a CMSampleBuffer (zero-copy).
    func detectAsync(sampleBuffer: CMSampleBuffer, timestampMs: Int) {
        guard let poseLandmarker = poseLandmarker else { return }

        do {
            let mpImage = try MPImage(sampleBuffer: sampleBuffer)
            try poseLandmarker.detectAsync(image: mpImage, timestampInMilliseconds: timestampMs)
        } catch {
            delegate?.poseLandmarkerHelper(self, didFailWithError: "Detection failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Lifecycle

    func clearPoseLandmarker() {
        poseLandmarker = nil
    }

    func updateConfig(
        delegate delegateType: Int? = nil,
        model: Int? = nil,
        minPoseDetectionConfidence: Float? = nil,
        minPoseTrackingConfidence: Float? = nil,
        minPosePresenceConfidence: Float? = nil
    ) {
        if let d = delegateType { currentDelegate = d }
        if let m = model { currentModel = m }
        if let c = minPoseDetectionConfidence { self.minPoseDetectionConfidence = c }
        if let c = minPoseTrackingConfidence { self.minPoseTrackingConfidence = c }
        if let c = minPosePresenceConfidence { self.minPosePresenceConfidence = c }

        clearPoseLandmarker()
        setupPoseLandmarker()
    }

    // MARK: - Bundle resolution

    /// Find the resource bundle that contains the .task model files.
    private static func modelBundle() -> Bundle {
        // When distributed as a plugin, resources are in a resource bundle
        let mainBundle = Bundle(for: PoseLandmarkerHelper.self)
        if let resourceBundleURL = mainBundle.url(
            forResource: "flutter_mp_pose_landmarker_models",
            withExtension: "bundle"
        ), let resourceBundle = Bundle(url: resourceBundleURL) {
            return resourceBundle
        }
        // Fallback to the class bundle itself
        return mainBundle
    }
}

// MARK: - PoseLandmarkerLiveStreamDelegate

extension PoseLandmarkerHelper: PoseLandmarkerLiveStreamDelegate {
    func poseLandmarker(
        _ poseLandmarker: PoseLandmarker,
        didFinishDetection result: PoseLandmarkerResult?,
        timestampInMilliseconds: Int,
        error: (any Error)?
    ) {
        if let error = error {
            delegate?.poseLandmarkerHelper(self, didFailWithError: error.localizedDescription)
            return
        }

        guard let result = result else { return }

        // Extract 2D normalized landmarks
        var landmarks: [[String: Any]] = []
        for poseLandmarks in result.landmarks {
            for landmark in poseLandmarks {
                let vis = landmark.visibility?.floatValue ?? 1.0
                let pres = landmark.presence?.floatValue ?? 1.0
                landmarks.append([
                    "x": landmark.x,
                    "y": landmark.y,
                    "z": landmark.z,
                    "visibility": vis,
                    "presence": pres
                ])
            }
        }

        // Extract 3D world landmarks
        var worldLandmarks: [[String: Any]] = []
        for poseWorldLandmarks in result.worldLandmarks {
            for landmark in poseWorldLandmarks {
                let vis = landmark.visibility?.floatValue ?? 1.0
                let pres = landmark.presence?.floatValue ?? 1.0
                worldLandmarks.append([
                    "x": landmark.x,
                    "y": landmark.y,
                    "z": landmark.z,
                    "visibility": vis,
                    "presence": pres
                ])
            }
        }

        delegate?.poseLandmarkerHelper(
            self,
            didFinishDetectionWithLandmarks: landmarks,
            worldLandmarks: worldLandmarks,
            timestampMs: timestampInMilliseconds
        )
    }
}

// MARK: - Delegate Protocol

protocol PoseLandmarkerHelperDelegate: AnyObject {
    func poseLandmarkerHelper(
        _ helper: PoseLandmarkerHelper,
        didFinishDetectionWithLandmarks landmarks: [[String: Any]],
        worldLandmarks: [[String: Any]],
        timestampMs: Int
    )
    func poseLandmarkerHelper(_ helper: PoseLandmarkerHelper, didFailWithError error: String)
}
