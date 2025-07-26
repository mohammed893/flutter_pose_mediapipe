import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_mp_pose_landmarker_platform_interface.dart';

/// An implementation of [FlutterMpPoseLandmarkerPlatform] that uses method channels.
class MethodChannelFlutterMpPoseLandmarker extends FlutterMpPoseLandmarkerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_mp_pose_landmarker');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
