import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_mp_pose_landmarker_method_channel.dart';

abstract class FlutterMpPoseLandmarkerPlatform extends PlatformInterface {
  /// Constructs a FlutterMpPoseLandmarkerPlatform.
  FlutterMpPoseLandmarkerPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterMpPoseLandmarkerPlatform _instance = MethodChannelFlutterMpPoseLandmarker();

  /// The default instance of [FlutterMpPoseLandmarkerPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterMpPoseLandmarker].
  static FlutterMpPoseLandmarkerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterMpPoseLandmarkerPlatform] when
  /// they register themselves.
  static set instance(FlutterMpPoseLandmarkerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
