Pod::Spec.new do |s|
  s.name             = 'flutter_mp_pose_landmarker'
  s.version          = '0.1.5'
  s.summary          = 'Flutter plugin for real-time pose detection using MediaPipe.'
  s.description      = <<-DESC
A Flutter plugin for real-time pose detection using MediaPipe Pose Landmarker
with native CameraX (Android) and AVFoundation (iOS) integration.
                       DESC
  s.homepage         = 'https://github.com/useronym/flutter_pose_mediapipe'
  s.license          = { :type => 'Apache-2.0', :file => '../LICENSE' }
  s.author           = { 'Author' => 'author@example.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'MediaPipeTasksVision', '~> 0.10.14'
  s.static_framework = true

  s.platform = :ios, '16.0'
  s.ios.deployment_target = '16.0'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'FRAMEWORK_SEARCH_PATHS' => '$(inherited) "$(PODS_CONFIGURATION_BUILD_DIR)/MediaPipeTasksVision" "$(PODS_CONFIGURATION_BUILD_DIR)/MediaPipeTasksCommon"',
    'SWIFT_INCLUDE_PATHS' => '$(inherited) "$(PODS_CONFIGURATION_BUILD_DIR)/MediaPipeTasksVision" "$(PODS_CONFIGURATION_BUILD_DIR)/MediaPipeTasksCommon"'
  }
  s.swift_version = '5.0'

  # MediaPipe model files are bundled with the plugin
  s.resource_bundles = {
    'flutter_mp_pose_landmarker_models' => ['Assets/**/*.task']
  }
end
