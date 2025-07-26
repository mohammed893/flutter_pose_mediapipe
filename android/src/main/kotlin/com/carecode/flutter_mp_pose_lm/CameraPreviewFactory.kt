package com.carecode.flutter_mp_pose_lm

import android.content.Context
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec

class CameraPreviewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, id: Int, args: Any?): PlatformView {
        return CameraPreview(context)
    }
}