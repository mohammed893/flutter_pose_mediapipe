# Flutter MP Pose Landmarker Plugin

A Flutter plugin to integrate MediaPipe Pose Landmarker for real-time human pose detection using native Android (CameraX) and iOS implementations.

## Features

- Real-time pose detection with landmark coordinates.
- Native Android support using CameraX.
- Improved landmark accuracy using `presence` instead of `visibility`.
- Initial iOS support.
- Stream-based detection via EventChannel.
- Example app with hand detection.

## Requirements

To use this plugin, make sure your environment meets the following:

- **Flutter SDK**: `>=3.13.0`
- **Android SDK**: `>=24` (minimum supported)
- **iOS**: `>=16.0`
- **JDK**: `17` or higher (required for AGP 8.0+)
- **Kotlin**: `1.9.10` or higher
- **Gradle**: `8.0+` (recommended 8.2+)

## Supported Platforms

- Android
- iOS (initial support, still under testing)

> Note: Web is not supported due to native camera and MediaPipe dependencies.

## Getting Started

1. Add the dependency:

```yaml
dependencies:
  flutter_mp_pose_landmarker:
    git:
      url: https://github.com/mohammed893/flutter_pose_mediapipe