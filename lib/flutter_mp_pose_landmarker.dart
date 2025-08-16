import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

/// Model for a single landmark point
class PoseLandmarkPoint {
  final double x;
  final double y;
  final double z;
  final double visibility;

  PoseLandmarkPoint({
    required this.x,
    required this.y,
    required this.z,
    required this.visibility,
  });

  factory PoseLandmarkPoint.fromJson(Map<String, dynamic> json) {
    return PoseLandmarkPoint(
      x: json['x'].toDouble(),
      y: json['y'].toDouble(),
      z: json['z'].toDouble(),
      visibility: json['visibility'].toDouble(),
    );
  }
}

/// Model for a detected pose with landmarks
class PoseLandMarker {
  final int timestampMs;
  final List<PoseLandmarkPoint> landmarks;

  PoseLandMarker({
    required this.timestampMs,
    required this.landmarks,
  });

  factory PoseLandMarker.fromJson(Map<String, dynamic> json) {
    var landmarkList = json['landmarks'] as List;
    List<PoseLandmarkPoint> landmarks = landmarkList
        .map((pointJson) => PoseLandmarkPoint.fromJson(pointJson))
        .toList();

    return PoseLandMarker(
      timestampMs: json['timestampMs'],
      landmarks: landmarks,
    );
  }
}

class PoseLandmarker {
  // Remove method channel - no longer needed
  static const EventChannel _eventChannel =
      EventChannel('pose_landmarker/events');
  static const MethodChannel _channel =
      MethodChannel("pose_landmarker/methods");

  static Stream<PoseLandMarker>? _poseStream;


  static Future<void> setConfig({
    required int delegate, // 0 = CPU, 1 = GPU
    required int model, // 0 = full, 1 = lite, 2 = heavy
  }) async {
    await _channel.invokeMethod("setConfig", {
      "delegate": delegate,
      "model": model,
    });
  }

  static Future<void> switchCamera() async {
  await _channel.invokeMethod('switchCamera');
  
  }

  /// Provides a broadcast stream of PoseLandMarker results
  static Stream<PoseLandMarker> get poseLandmarkStream {
    _poseStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((event) {
          try {
            final Map<String, dynamic> jsonMap = jsonDecode(event);
            return PoseLandMarker.fromJson(jsonMap);
          } catch (e) {
            print('PoseLandmarker: Error parsing event: $e');
            rethrow;
          }
        });
    return _poseStream!;
  }
}