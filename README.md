# Flutter MP Pose Landmarker Plugin

A Flutter plugin to integrate [MediaPipe's](https://mediapipe.dev/) Pose Landmarker using native Android (CameraX) for real-time human pose detection.

## Features

- Real-time pose detection with landmark coordinates.
- Native CameraX support.
- Customizable event channel for stream data.

## Requirements

To use this plugin, make sure your environment meets the following:

- **Flutter SDK**: `>=3.10.0`
- **Android SDK**: `>=21` (minimum supported)
- **JDK**: `17` or higher (required for AGP 8.0+)
- **Kotlin**: `1.9.10` or higher (already configured)
- **Gradle**: Use Gradle wrapper `8.0+` (recommended)
- **Supported Platforms**: Android only *(iOS coming soon / not supported)*

> ⚠️ This plugin uses CameraX and native Android, so it won't work on web or iOS (yet).

## Getting Started

1. Add the dependency:

```yaml
dependencies:
  flutter_mp_pose_landmarker:
    git:
      url: https://github.com/mohammed893/flutter_pose_mediapipe