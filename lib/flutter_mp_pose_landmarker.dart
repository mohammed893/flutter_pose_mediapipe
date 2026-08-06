import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

/// Model for a single normalized landmark point (image coordinates)
class PoseLandmarkPoint {
  final double x;
  final double y;
  final double z;
  final double visibility;
  final double presence;

  PoseLandmarkPoint({
    required this.x,
    required this.y,
    required this.z,
    required this.visibility,
    this.presence = 0.0,
  });

  Map<String, dynamic> toJson() {
    return {
      'x': x,
      'y': y,
      'z': z,
      'visibility': visibility,
      'presence': presence,
    };
  }

  factory PoseLandmarkPoint.fromJson(Map<String, dynamic> json) {
    return PoseLandmarkPoint(
      x: json['x'].toDouble(),
      y: json['y'].toDouble(),
      z: json['z'].toDouble(),
      visibility: (json['visibility'] ?? 0.0).toDouble(),
      presence: (json['presence'] ?? 0.0).toDouble(),
    );
  }
}

/// Model for a single world landmark point (real-world 3D coordinates in meters)
class WorldLandmarkPoint {
  final double x;
  final double y;
  final double z;
  final double visibility;
  final double presence;

  WorldLandmarkPoint({
    required this.x,
    required this.y,
    required this.z,
    required this.visibility,
    this.presence = 0.0,
  });

  Map<String, dynamic> toJson() {
    return {
      'x': x,
      'y': y,
      'z': z,
      'visibility': visibility,
      'presence': presence,
    };
  }

  factory WorldLandmarkPoint.fromJson(Map<String, dynamic> json) {
    return WorldLandmarkPoint(
      x: json['x'].toDouble(),
      y: json['y'].toDouble(),
      z: json['z'].toDouble(),
      visibility: (json['visibility'] ?? 0.0).toDouble(),
      presence: (json['presence'] ?? 0.0).toDouble(),
    );
  }
}

/// Model for a detected pose with landmarks and optional FPS
class PoseLandMarker {
  final int timestampMs;
  final List<PoseLandmarkPoint> landmarks;
  final List<WorldLandmarkPoint> worldLandmarks;
  final double? fps;

  PoseLandMarker({
    required this.timestampMs,
    required this.landmarks,
    this.worldLandmarks = const [],
    this.fps,
  });

  factory PoseLandMarker.fromJson(Map<String, dynamic> json) {
    var landmarkList = json['landmarks'] as List;
    List<PoseLandmarkPoint> landmarks = landmarkList
        .map((pointJson) => PoseLandmarkPoint.fromJson(
            pointJson is Map<String, dynamic>
                ? pointJson
                : Map<String, dynamic>.from(pointJson as Map)))
        .toList();

    List<WorldLandmarkPoint> worldLandmarks = [];
    if (json['worldLandmarks'] != null) {
      var worldList = json['worldLandmarks'] as List;
      worldLandmarks = worldList
          .map((pointJson) => WorldLandmarkPoint.fromJson(
              pointJson is Map<String, dynamic>
                  ? pointJson
                  : Map<String, dynamic>.from(pointJson as Map)))
          .toList();
    }

    return PoseLandMarker(
      timestampMs: json['timestampMs'],
      landmarks: landmarks,
      worldLandmarks: worldLandmarks,
      fps: json['fps']?.toDouble(),
    );
  }
}

class PoseLandmarker {
  static const EventChannel _eventChannel =
      EventChannel('pose_landmarker/events');
  static const MethodChannel _channel =
      MethodChannel("pose_landmarker/methods");

  static Stream<PoseLandMarker>? _poseStream;

  /// Sets configuration including delegate, model, and confidence thresholds
  static Future<void> setConfig({
    required int delegate, // 0 = CPU, 1 = GPU
    required int model, // 0 = full, 1 = lite, 2 = heavy
    double minPoseDetectionConfidence = 0.5,
    double minPoseTrackingConfidence = 0.5,
    double minPosePresenceConfidence = 0.5,
    int analysisWidth = 640,
    int analysisHeight = 480,
  }) async {
    await _channel.invokeMethod("setConfig", {
      "delegate": delegate,
      "model": model,
      "minPoseDetectionConfidence": minPoseDetectionConfidence,
      "minPoseTrackingConfidence": minPoseTrackingConfidence,
      "minPosePresenceConfidence": minPosePresenceConfidence,
      "analysisWidth": analysisWidth,
      "analysisHeight": analysisHeight,
    });
  }

  /// Switch between front/back camera
  static Future<void> switchCamera() async {
    await _channel.invokeMethod('switchCamera');
  }

  /// Get current camera ("front" or "back")
  static Future<String> getCurrentCamera() async {
    final camera = await _channel.invokeMethod<String>('getCurrentCamera');
    return camera ?? "back"; // fallback
  }

  static Future<void> resetCamera() async {
      await _channel.invokeMethod('resetCamera');
  }
  /// Enable or disable logging on native side
  static Future<void> setLoggingEnabled(bool enabled) async {
    await _channel.invokeMethod('setLoggingEnabled', {"enabled": enabled});
  }

  /// Pause pose detection without stopping the camera
  static Future<void> pauseDetection() async {
    await _channel.invokeMethod('pauseAnalysis');
  }

  /// Resume pose detection while keeping the camera live
  static Future<void> resumeDetection() async {
    await _channel.invokeMethod('resumeAnalysis');
  }

  /// Request camera permission at runtime. Returns true if granted.
  static Future<bool> requestCameraPermission() async {
    final granted =
        await _channel.invokeMethod<bool>('requestCameraPermission');
    return granted ?? false;
  }

  /// Check if camera permission is already granted.
  static Future<bool> checkCameraPermission() async {
    final granted =
        await _channel.invokeMethod<bool>('checkCameraPermission');
    return granted ?? false;
  }

  /// Whether the camera preview is currently mirrored (true for real front cameras).
  /// Use this to decide if skeleton overlay needs x-axis flipping.
  static Future<bool> isPreviewMirrored() async {
    final mirrored = await _channel.invokeMethod<bool>('isPreviewMirrored');
    return mirrored ?? false;
  }

  /// Whether the app is running on an emulator/simulator.
  static Future<bool> isEmulator() async {
    final emu = await _channel.invokeMethod<bool>('isEmulator');
    return emu ?? false;
  }

  /// Provides a broadcast stream of PoseLandMarker results
  static Stream<PoseLandMarker> get poseLandmarkStream {
    _poseStream ??= _eventChannel.receiveBroadcastStream().map((event) {
      try {
        final Map<String, dynamic> jsonMap = jsonDecode(event);
        return PoseLandMarker.fromJson(jsonMap);
      } catch (e) {
        rethrow;
      }
    });
    return _poseStream!;
  }
}
