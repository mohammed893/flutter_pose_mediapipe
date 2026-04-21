# Changelog

## [0.1.7] - 2026-04-21
### Improvements
- Improved camera stream stability with safer helper initialization.
- Added frame timestamp validation to prevent duplicate/out-of-order processing.
- Introduced camera state flags for better lifecycle handling.

### Pose Processing
- Fixed live stream inference timing using `imageProxy` timestamps.
- Reduced MediaPipe warnings by filtering invalid frames.
- Improved GPU → CPU fallback handling and logging.

### Image Processing
- Made YUV → RGB conversion thread-safe.
- Fixed NV21 conversion (correct stride/pixel handling).
- Added strict bitmap validation (size, config, and recycling checks).

### Stability
- Ensured `imageProxy.close()` is always called to avoid pipeline blocking.
- Reduced race conditions in live stream processing.

### Cleanup
- Refactored helper initialization and removed redundant null checks.
- Simplified bitmap rotation and MediaPipe pipeline setup.



## [0.1.6] - 2026-04-13
### Added
- Initial iOS support (requires further testing)
- Hand detection implementation in example app

### Fixed
- Fixed issue where landmark `visibility` and `presence` defaulted to high values even when landmarks were not detected

## [0.1.5] - 2026-03-17

### Fixed
- Camera is no longer initialized eagerly on activity attach. It now starts
  only when a Dart listener subscribes to `poseLandmarkStream` (`onListen`)
  and is fully released when the subscription is cancelled (`onCancel`).
  This fixes a conflict where the plugin held the camera hardware even when
  no pose detection was active, preventing the `camera` package and other
  consumers from opening the camera on other screens.

### Changed
- `CameraManager.startCamera()` is no longer called inside `onAttachedToActivity`.
  The plugin now only constructs the manager at attach time and defers all
  hardware access to stream subscription time.
- `onCancel` now calls `releaseCamera()` (unbinds `ProcessCameraProvider`)
  in addition to `disableAnalysis()`, ensuring the hardware is fully freed.
- Stream listener guards added (`!mounted` check) to prevent `setState`
  calls after widget disposal.
- `IPoseManager` extended with `releaseCamera()` to formalize the release
  contract across both `CameraManager` and `MockPoseManager`.
- `MockPoseManager.releaseCamera()` implemented as a no-op.

### Example app
- `_poseSubscription` changed from `late` to nullable (`?`) so `dispose()`
  is safe even if the stream was never started.
- Replaced deprecated `withOpacity()` calls with `withAlpha()`.
- Replaced `print()` calls with `debugPrint()`.
- Repeated stat label containers extracted into a `_StatChip` widget.



## [0.1.4] - 2026-03-13

### Added
#### Security & Anti-Spoofing
- **Emulator Detection System**
  - Created `EmulatorDetector` utility class to identify Android emulators at runtime
  - Provides boolean flag for emulator presence

- **Mock Pose Manager**
  - Implemented `MockPoseManager` class as safe fallback for emulator environments
  - Prevents pose analysis manipulation on virtual devices
  - Returns mock/empty pose data to maintain API consistency
  - Implements `IPoseManager` interface for seamless integration

#### Architecture Improvements
- **IPoseManager Interface**
  - Created abstraction layer for pose management
  - Defined contract methods:
    - `setEventSink(events: EventChannel.EventSink?)`
    - `enableAnalysis()`
    - `disableAnalysis()`
    - `pauseAnalysis()`
    - `resumeAnalysis()`
    - `dispose()`
  - Enables strategy pattern for real vs. mock pose managers

### Changed

#### CameraManager Refactoring
- **Interface Implementation** (`CameraManager.kt`)
  - Implemented `IPoseManager` interface
  - Added `override` modifiers to interface methods:
    - Line 179: `override fun setEventSink()`
    - Line 183: `override fun enableAnalysis()`
    - Line 212: `override fun disableAnalysis()`
    - Line 219: `override fun pauseAnalysis()`
    - Line 225: `override fun resumeAnalysis()`
    - Line 231: `override fun dispose()`
  - Ensures compile-time contract enforcement

#### Plugin Initialization
- **FlutterMpPoseLandmarkerPlugin Updates** (`FlutterMpPoseLandmarkerPlugin.kt`)
  - Line 117: Added emulator detection on plugin initialization
```kotlin
    poseManager = if (EmulatorDetector.isEmulator(context)) {
        MockPoseManager()
    } else {
        CameraManager(context, textureRegistry)
    }
```
  - Line 131: Updated pose manager references to use abstracted interface
  - Dynamically selects real or mock manager based on device type

### Security Impact
- **Data Integrity Protection**
  - Prevents spoofing of pose landmark data on emulated devices
  - Ensures exercises are performed on real devices
  - Protects against automated bot manipulation in fitness apps
  - Maintains trust in pose tracking for medical/therapeutic use cases
  - Making Sure app devs has no problem working with emulators with the mock service

### Technical Details
- **Files Modified:**
  - `android/src/main/kotlin/com/carecode/flutter_mp_pose_lm/CameraManager.kt`
  - `android/src/main/kotlin/com/carecode/flutter_mp_pose_lm/FlutterMpPoseLandmarkerPlugin.kt`
  
- **Files Added:**
  - `android/src/main/kotlin/com/carecode/flutter_mp_pose_lm/IPoseManager.kt` (interface)
  - `android/src/main/kotlin/com/carecode/flutter_mp_pose_lm/MockPoseManager.kt`
  - `android/src/main/kotlin/com/carecode/flutter_mp_pose_lm/EmulatorDetector.kt`

- **Dependencies:**
  - No new external dependencies required
  - Uses Android Build class for device detection
  - Compatible with existing MediaPipe pose detection pipeline

### Breaking Changes
- None - Changes are backward compatible with existing API

### Migration Guide
No migration required. The changes are transparent to Flutter/Dart layer consumers.
Existing code will continue to work without modifications.


## [0.1.3] - 2025-12-04
### Fixed
- Removed leftover `print()` debug statements.
- Updated license information and added LICENSE file.

---

## [0.1.2] - 2025-11-28
### Added
- Camera permission check before starting detection.
- Configurable confidence thresholds:
  - `minPoseDetectionConfidence`
  - `minPoseTrackingConfidence`
  - `minPosePresenceConfidence`
- Runtime configuration via `PoseLandmarker.setConfig()`.
- Camera controls:
  - Start / Stop / Pause / Resume detection.
- Logging toggle to enable/disable plugin logs.
- FPS counter for performance monitoring.
- Platform interface tests.
- Integration tests.

### Changed
- All confidence thresholds can now be updated dynamically at runtime via `setConfig()`.

---

## [0.1.1] - 2025-11-21
### Added
- Camera lens swapping (front ↔ back).
- PoseLandmarker configuration:
  - Model type (lite, full, heavy).
  - Delegate (CPU or GPU).

### Fixed
- Detection stopping after camera swap.

---

## [0.0.1] - 2025-07-26
### Added
- Initial pose detection functionality using MediaPipe.
- Android implementation with CameraX.
- Base configuration system for PoseLandmarker.