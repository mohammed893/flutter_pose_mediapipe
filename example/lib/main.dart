import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_mp_pose_landmarker/flutter_mp_pose_landmarker.dart';

Future<void> ensureCameraPermission() async {
  final granted = await PoseLandmarker.checkCameraPermission();
  if (!granted) {
    await PoseLandmarker.requestCameraPermission();
  }
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ensureCameraPermission();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Pose Landmarker Demo',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const PoseLandmarkerView(),
    );
  }
}

class PoseLandmarkerView extends StatefulWidget {
  const PoseLandmarkerView({super.key});
  @override
  State<PoseLandmarkerView> createState() => _PoseLandmarkerViewState();
}

// Preset analysis resolutions — lower = faster, less accurate.
enum _AnalysisResolution {
  low(320, 240, 'Low (320x240)'),
  medium(480, 360, 'Medium (480x360)'),
  high(640, 480, 'High (640x480)');

  final int width;
  final int height;
  final String label;
  const _AnalysisResolution(this.width, this.height, this.label);
}

class _PoseLandmarkerViewState extends State<PoseLandmarkerView> {
  int delegate = 1;
  int model = 1;

  double _minPoseDetectionConfidence = 0.5;
  double _minPoseTrackingConfidence = 0.5;
  double _minPosePresenceConfidence = 0.9;

  // Analysis resolution — this is what actually drives perf, independent
  // of what the preview looks like on screen.
  _AnalysisResolution _analysisResolution = _AnalysisResolution.medium;

  List<PoseLandmarkPoint> _landmarks = [];

  // Hand detection states
  bool _leftHandDetected = false;
  bool _rightHandDetected = false;

  // Nullable so dispose() is safe even if stream never started
  StreamSubscription<PoseLandMarker>? _poseSubscription;

  bool _detectionPaused = false;
  bool _loggingEnabled = true;
  String _cameraLens = 'Back';

  int _fps = 0;


  final _detectionController = TextEditingController();
  final _trackingController = TextEditingController();
  final _presenceController = TextEditingController();

  /// Detects if hands are visible based on wrist and finger landmarks.
  /// In MediaPipe pose: 
  /// - Left wrist: 15, Left fingers: 17-21
  /// - Right wrist: 16, Right fingers: 22-26
  /// Uses 'presence' confidence score which is more reliable than visibility
  void _detectHands(List<PoseLandmarkPoint> landmarks) {
    const double handPresenceThreshold = 0.3; // More conservative threshold
    
    bool leftHandDetected = false;
    bool rightHandDetected = false;
    
    if (landmarks.length > 26) {
      // Check left hand (wrist 15 + fingers 17-21)
      double leftHandPresence = 0;
      for (int i in [15, 17, 18, 19, 20, 21]) {
        leftHandPresence += landmarks[i].presence;
      }
      double leftAvg = leftHandPresence / 6;
      leftHandDetected = leftAvg >= handPresenceThreshold;
      
      // Check right hand (wrist 16 + fingers 22-26)
      double rightHandPresence = 0;
      for (int i in [16, 22, 23, 24, 25, 26]) {
        rightHandPresence += landmarks[i].presence;
      }
      double rightAvg = rightHandPresence / 6;
      rightHandDetected = rightAvg >= handPresenceThreshold;
      
      if (_loggingEnabled) {
        debugPrint('Hand detection - Left: $leftAvg, Right: $rightAvg');
      }
    }
    
    if (_leftHandDetected != leftHandDetected || _rightHandDetected != rightHandDetected) {
      setState(() {
        _leftHandDetected = leftHandDetected;
        _rightHandDetected = rightHandDetected;
      });
    }
  }

  @override
  void initState() {
    super.initState();

    _detectionController.text = _minPoseDetectionConfidence.toString();
    _trackingController.text = _minPoseTrackingConfidence.toString();
    _presenceController.text = _minPosePresenceConfidence.toString();

    PoseLandmarker.setConfig(
      delegate: delegate,
      model: model,
      minPoseDetectionConfidence: _minPoseDetectionConfidence,
      minPoseTrackingConfidence: _minPoseTrackingConfidence,
      minPosePresenceConfidence: _minPosePresenceConfidence,
      analysisWidth: _analysisResolution.width,
      analysisHeight: _analysisResolution.height,
    );

    // Subscribing here triggers onListen in the plugin → startCamera() runs.
    // Cancelling in dispose() triggers onCancel → releaseCamera() runs,
    // freeing the hardware so other packages like camera can use it freely.
       _poseSubscription = PoseLandmarker.poseLandmarkStream.listen((pose) {
      if (_detectionPaused || !mounted) return;
      setState(() {
        _landmarks = pose.landmarks;
        if (_landmarks.isNotEmpty) {
          print("Visibility: ${_landmarks[26].visibility ?? 'No'}");
        }
        _fps = pose.fps!.round(); // ← native FPS from CameraManager, not Flutter-side counting
        if (_loggingEnabled) debugPrint('FPS: $_fps');
      });
      // Detect hands from landmarks
      _detectHands(pose.landmarks);
    });
  }

  void _switchCamera() {
    PoseLandmarker.switchCamera();
    setState(() {
      _cameraLens = _cameraLens == 'Back' ? 'Front' : 'Back';
    });
    if (_loggingEnabled) debugPrint('Switched camera to $_cameraLens');
  }

  void _toggleLogging() {
    setState(() => _loggingEnabled = !_loggingEnabled);
    debugPrint('Logging: $_loggingEnabled');
  }

  void _pauseResumeDetection() {
    setState(() => _detectionPaused = !_detectionPaused);
    if (_loggingEnabled) {
      debugPrint(_detectionPaused ? 'Detection paused' : 'Detection resumed');
    }
  }

  void _applyConfidenceSettings() {
    setState(() {
      _minPoseDetectionConfidence =
          double.tryParse(_detectionController.text) ?? 0.5;
      _minPoseTrackingConfidence =
          double.tryParse(_trackingController.text) ?? 0.5;
      _minPosePresenceConfidence =
          double.tryParse(_presenceController.text) ?? 0.5;

      PoseLandmarker.setConfig(
        delegate: delegate,
        model: model,
        minPoseDetectionConfidence: _minPoseDetectionConfidence,
        minPoseTrackingConfidence: _minPoseTrackingConfidence,
        minPosePresenceConfidence: _minPosePresenceConfidence,
        analysisWidth: _analysisResolution.width,
        analysisHeight: _analysisResolution.height,
      );

      if (_loggingEnabled) {
        debugPrint('Confidence updated — '
            'detection: $_minPoseDetectionConfidence, '
            'tracking: $_minPoseTrackingConfidence, '
            'presence: $_minPosePresenceConfidence, '
            'analysisRes: ${_analysisResolution.width}x${_analysisResolution.height}');
      }
    });
  }

  void _onResolutionChanged(_AnalysisResolution? newRes) {
    if (newRes == null || newRes == _analysisResolution) return;
    setState(() => _analysisResolution = newRes);

    // Push the new analysis resolution down immediately — no need to wait
    // for the "Apply" button, since this isn't a text field.
    PoseLandmarker.setConfig(
      delegate: delegate,
      model: model,
      minPoseDetectionConfidence: _minPoseDetectionConfidence,
      minPoseTrackingConfidence: _minPoseTrackingConfidence,
      minPosePresenceConfidence: _minPosePresenceConfidence,
      analysisWidth: newRes.width,
      analysisHeight: newRes.height,
    );

    if (_loggingEnabled) {
      debugPrint('Analysis resolution changed to ${newRes.width}x${newRes.height}');
    }
  }

  @override
  void dispose() {
    // Cancelling the subscription triggers onCancel in the plugin,
    // which calls releaseCamera() → ProcessCameraProvider.unbindAll()
    // → hardware is free for the camera package or any other consumer.
    _poseSubscription?.cancel();
    _detectionController.dispose();
    _trackingController.dispose();
    _presenceController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      bottomNavigationBar: Padding(
        padding: const EdgeInsets.all(8.0),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            FloatingActionButton(
              heroTag: 'switchCamera',
              onPressed: _switchCamera,
              child: const Icon(Icons.cameraswitch),
            ),
            FloatingActionButton(
              heroTag: 'pauseResume',
              onPressed: _pauseResumeDetection,
              child: Icon(_detectionPaused ? Icons.play_arrow : Icons.pause),
            ),
            FloatingActionButton(
              heroTag: 'loggingToggle',
              onPressed: _toggleLogging,
              child: const Icon(Icons.bug_report),
            ),
          ],
        ),
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          const NativeCameraPreview(),
          Positioned(
            top: 16,
            right: 16,
            width: 150,
            height: 150,
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white, width: 2),
                color: Colors.black.withAlpha(76),
              ),
              child: CustomPaint(
                painter: LandmarkPainter(_landmarks),
              ),
            ),
          ),
          Positioned(
            top: 16,
            left: 16,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _StatChip('Landmarks: ${_landmarks.length}'),
                const SizedBox(height: 4),
                _StatChip('Camera: $_cameraLens'),
                const SizedBox(height: 4),
                _StatChip('FPS: $_fps'),
                const SizedBox(height: 4),
                _StatChip(
                  'Hands: ${_leftHandDetected ? '👋L' : 'L'} ${_rightHandDetected ? '👋R' : 'R'}',
                ),
                const SizedBox(height: 4),
                // Analysis resolution picker — lets you trade perf vs accuracy live.
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  color: Colors.black54,
                  child: DropdownButtonHideUnderline(
                    child: DropdownButton<_AnalysisResolution>(
                      value: _analysisResolution,
                      dropdownColor: Colors.black87,
                      style: const TextStyle(color: Colors.white),
                      onChanged: _onResolutionChanged,
                      items: _AnalysisResolution.values
                          .map((res) => DropdownMenuItem(
                                value: res,
                                child: Text(res.label),
                              ))
                          .toList(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            bottom: 16,
            left: 16,
            right: 16,
            child: Container(
              padding: const EdgeInsets.all(8),
              color: Colors.black45,
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _detectionController,
                          decoration: const InputDecoration(
                            labelText: 'Detection Confidence',
                            fillColor: Colors.white,
                            filled: true,
                          ),
                          keyboardType: const TextInputType.numberWithOptions(
                              decimal: true),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          controller: _trackingController,
                          decoration: const InputDecoration(
                            labelText: 'Tracking Confidence',
                            fillColor: Colors.white,
                            filled: true,
                          ),
                          keyboardType: const TextInputType.numberWithOptions(
                              decimal: true),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          controller: _presenceController,
                          decoration: const InputDecoration(
                            labelText: 'Presence Confidence',
                            fillColor: Colors.white,
                            filled: true,
                          ),
                          keyboardType: const TextInputType.numberWithOptions(
                              decimal: true),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  ElevatedButton(
                    onPressed: _applyConfidenceSettings,
                    child: const Text('Apply'),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────

class _StatChip extends StatelessWidget {
  final String text;
  const _StatChip(this.text);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(8),
      color: Colors.black54,
      child: Text(text, style: const TextStyle(color: Colors.white)),
    );
  }
}

class NativeCameraPreview extends StatelessWidget {
  const NativeCameraPreview({super.key});
  @override
  Widget build(BuildContext context) {
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return const SizedBox.expand(
          child: AndroidView(
            viewType: 'camera_preview_view',
            layoutDirection: TextDirection.ltr,
          ),
        );
      case TargetPlatform.iOS:
        return const SizedBox.expand(
          child: UiKitView(
            viewType: 'camera_preview_view',
            layoutDirection: TextDirection.ltr,
          ),
        );
      default:
        return const Center(
          child: Text('Native camera preview is only supported on Android and iOS.'),
        );
    }
  }
}

// ─────────────────────────────────────────────
//  Landmark painter
// ─────────────────────────────────────────────

class LandmarkPainter extends CustomPainter {
  final List<PoseLandmarkPoint> landmarks;
  const LandmarkPainter(this.landmarks);

  static const List<List<int>> connections = [
    [0, 1], [1, 2], [2, 3], [3, 7],
    [0, 4], [4, 5], [5, 6], [6, 8],
    [9, 10],
    [11, 12], [11, 13], [13, 15], [12, 14], [14, 16],
    [11, 23], [12, 24], [23, 24],
    [23, 25], [25, 27], [24, 26], [26, 28],
    [27, 31], [28, 32],
    [15, 17], [16, 18], [17, 19], [18, 20], [19, 21], [20, 22],
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final pointPaint = Paint()
      ..color = Colors.red
      ..style = PaintingStyle.fill;

    final linePaint = Paint()
      ..color = Colors.blue
      ..strokeWidth = 2;

    for (final c in connections) {
      if (c[0] < landmarks.length && c[1] < landmarks.length) {
        final a = landmarks[c[0]];
        final b = landmarks[c[1]];
        canvas.drawLine(
          Offset(a.x * size.width, a.y * size.height),
          Offset(b.x * size.width, b.y * size.height),
          linePaint,
        );
      }
    }

    for (final lm in landmarks) {
      canvas.drawCircle(
        Offset(lm.x * size.width, lm.y * size.height),
        4,
        pointPaint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant LandmarkPainter oldDelegate) =>
      oldDelegate.landmarks != landmarks;
}