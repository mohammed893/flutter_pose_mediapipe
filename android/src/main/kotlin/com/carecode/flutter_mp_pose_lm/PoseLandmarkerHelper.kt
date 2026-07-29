/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carecode.flutter_mp_pose_lm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Canvas

class PoseLandmarkerHelper(
        var minPoseDetectionConfidence: Float = DEFAULT_POSE_DETECTION_CONFIDENCE,
        var minPoseTrackingConfidence: Float = DEFAULT_POSE_TRACKING_CONFIDENCE,
        var minPosePresenceConfidence: Float = DEFAULT_POSE_PRESENCE_CONFIDENCE,
        var currentModel: Int = MODEL_POSE_LANDMARKER_LITE,
        var currentDelegate: Int = DELEGATE_GPU,
        var runningMode: RunningMode = RunningMode.IMAGE,
        val context: Context,

        // this listener is only used when running in RunningMode.LIVE_STREAM
        val poseLandmarkerHelperListener: LandmarkerListener? = null
) {

    // For this example this needs to be a var so it can be reset on changes.
    // If the Pose Landmarker will not change, a lazy val would be preferable.
    private var poseLandmarker: PoseLandmarker? = null
    private var bitmapBuffer: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var rotatedCanvas: Canvas? = null   
    private val isClosed = AtomicBoolean(false)
    private val yuvToRgbConverter by lazy { YuvToRgbConverter(context) }
    private var lastDetectedTimestamp = Long.MIN_VALUE



    init {
        Log.d(TAG, "PoseLandmarkerHelper init: model=$currentModel, delegate=$currentDelegate, runningMode=$runningMode, API=${android.os.Build.VERSION.SDK_INT}")
        Log.d(TAG, "Device Info: Brand=${android.os.Build.BRAND}, Model=${android.os.Build.MODEL}, CPU_ABI=${android.os.Build.CPU_ABI}, CPU_ABI2=${android.os.Build.CPU_ABI2}")
        Log.d(TAG, "CPU_ABI (primary): ${android.os.Build.CPU_ABI}")
        Log.d(TAG, "Supported ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        setupPoseLandmarker()
        Log.d(TAG, "PoseLandmarkerHelper initialized successfully")
    }

    fun clearPoseLandmarker() {
    // Mark as closed so detectLiveStream stops doing work
    if (!isClosed.compareAndSet(false, true)) {
        return
    }

    try {
        poseLandmarker?.close()
    } catch (t: Throwable) {
        Log.e(TAG, "Error closing poseLandmarker", t)
    } finally {
        poseLandmarker = null
    }

    // Clean up bitmap and RenderScript resources
    try {
        yuvToRgbConverter.destroy()
    } catch (_: Throwable) {}
    try {
        bitmapBuffer?.recycle()
        bitmapBuffer = null
        rotatedBitmap?.recycle()
        rotatedBitmap = null
        rotatedCanvas = null
    } catch (_: Throwable) {}
}

    // Return running status of PoseLandmarkerHelper
    fun isClose(): Boolean {
        return poseLandmarker == null
    }

    fun setupPoseLandmarker() {
        Log.d(TAG, "setupPoseLandmarker() called - isClosed=${isClosed.get()}")
        isClosed.set(false)
        // Set general pose landmarker options
        val baseOptionBuilder = BaseOptions.builder()
        Log.d(TAG, "BaseOptions builder created")

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                Log.d(TAG, "Setting delegate to CPU")
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }
            DELEGATE_GPU -> {
                Log.d(TAG, "Setting delegate to GPU")
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        val modelName =
                when (currentModel) {
                    MODEL_POSE_LANDMARKER_FULL -> "pose_landmarker_full.task"
                    MODEL_POSE_LANDMARKER_LITE -> "pose_landmarker_lite.task"
                    MODEL_POSE_LANDMARKER_HEAVY -> "pose_landmarker_heavy.task"
                    else -> "pose_landmarker_full.task"
                }
        Log.d(TAG, "Using model: $modelName")
        
        // Verify model file exists
        try {
            val modelStream = context.assets.open(modelName)
            modelStream.close()
            Log.d(TAG, "Model file '$modelName' found in assets")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Model file '$modelName' NOT FOUND in assets! Exception: ${e.message}", e)
            poseLandmarkerHelperListener?.onError("Model file '$modelName' not found in assets")
            throw RuntimeException("Model file '$modelName' not found", e)
        }
        
        baseOptionBuilder.setModelAssetPath(modelName)

        // Check if runningMode is consistent with poseLandmarkerHelperListener
        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                Log.d(TAG, "Running mode is LIVE_STREAM, listener=${poseLandmarkerHelperListener != null}")
                if (poseLandmarkerHelperListener == null) {
                    throw IllegalStateException(
                            "poseLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
                    )
                }
            }
            else -> {
                Log.d(TAG, "Running mode: $runningMode")
            }
        }

        try {
            Log.d(TAG, "Building BaseOptions...")
            val baseOptions = baseOptionBuilder.build()
            Log.d(TAG, "BaseOptions built with delegate=$currentDelegate, model=$modelName")
        
            Log.d(TAG, "Building PoseLandmarkerOptions...")
            // Create an option builder with base options and specific
            // options only use for Pose Landmarker.
            val optionsBuilder =
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                            .setMinTrackingConfidence(minPoseTrackingConfidence)
                            .setMinPosePresenceConfidence(minPosePresenceConfidence)
                            .setRunningMode(runningMode)
            Log.d(TAG, "PoseLandmarkerOptions builder configured")

            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
            if (runningMode == RunningMode.LIVE_STREAM) {
                Log.d(TAG, "Setting result and error listeners for LIVE_STREAM")
                optionsBuilder
                        .setResultListener(this::returnLivestreamResult)
                        .setErrorListener(this::returnLivestreamError)
            }

            Log.d(TAG, "Building final PoseLandmarkerOptions...")
            val options = optionsBuilder.build()
            Log.d(TAG, "PoseLandmarkerOptions built, creating PoseLandmarker...")
            
            try {
                Log.d(TAG, "About to call PoseLandmarker.createFromOptions()")
                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                Log.d(TAG, "PoseLandmarker created successfully!")
            } catch (nativeEx: UnsatisfiedLinkError) {
                Log.e(TAG, "NATIVE LIBRARY ERROR - Missing or incompatible native MediaPipe libraries", nativeEx)
                throw nativeEx
            } catch (createEx: Exception) {
                Log.e(TAG, "Exception during createFromOptions - Type: ${createEx.javaClass.simpleName}", createEx)
                createEx.printStackTrace()
                throw createEx
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException during PoseLandmarker initialization: ${e.message}", e)
            poseLandmarkerHelperListener?.onError(
                    "Pose Landmarker failed to initialize. See error logs for " + "details"
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "RuntimeException during PoseLandmarker initialization: ${e.message}", e)
            // GPU delegate failed — auto-fallback to CPU
            if (currentDelegate == DELEGATE_GPU) {
                Log.w(TAG, "GPU delegate failed, falling back to CPU: ${e.message}")
                currentDelegate = DELEGATE_CPU
                try {
                    Log.d(TAG, "Creating CPU fallback PoseLandmarker...")
                    val cpuBaseOptions =
                            BaseOptions.builder()
                                    .setDelegate(Delegate.CPU)
                                    .setModelAssetPath(modelName)
                                    .build()
                    val cpuOptions =
                            PoseLandmarker.PoseLandmarkerOptions.builder()
                                    .setBaseOptions(cpuBaseOptions)
                                    .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                                    .setMinTrackingConfidence(minPoseTrackingConfidence)
                                    .setMinPosePresenceConfidence(minPosePresenceConfidence)
                                    .setRunningMode(runningMode)
                    if (runningMode == RunningMode.LIVE_STREAM) {
                        cpuOptions
                                .setResultListener(this::returnLivestreamResult)
                                .setErrorListener(this::returnLivestreamError)
                    }
                    poseLandmarker = PoseLandmarker.createFromOptions(context, cpuOptions.build())
                    Log.i(TAG, "Successfully fell back to CPU delegate")
                } catch (cpuError: Exception) {
                    Log.e(TAG, "CPU fallback failed: ${cpuError.message}", cpuError)
                    poseLandmarkerHelperListener?.onError(
                            "Pose Landmarker failed to initialize with both GPU and CPU."
                    )
                }
            } else {
                poseLandmarkerHelperListener?.onError(
                        "Pose Landmarker failed to initialize. See error logs for details",
                        GPU_ERROR
                )
                Log.e(TAG, "Model load failed: ${e.message}")
            }
        }
    }

    // ------------------------------
    // New method to dynamically update delegate, model, and detection confidence
    fun updateConfig(
            delegate: Int? = null,
            model: Int? = null,
            minPoseDetectionConfidence: Float? = null
    ) {
        delegate?.let { currentDelegate = it }
        model?.let { currentModel = it }
        minPoseDetectionConfidence?.let { this.minPoseDetectionConfidence = it }

        clearPoseLandmarker()
        setupPoseLandmarker()
    }
    // ------------------------------

    // Convert the ImageProxy to MP Image and feed it to PoselandmakerHelper.
    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        Log.v(TAG, "detectLiveStream() called - isFrontCamera=$isFrontCamera, isClosed=${isClosed.get()}")
        if (runningMode != RunningMode.LIVE_STREAM) {
            Log.e(TAG, "detectLiveStream called with wrong running mode: $runningMode")
            imageProxy.close()
            throw IllegalArgumentException(
                    "Running mode must be LIVE_STREAM to call detectLiveStream"
            )
        }

        // If we are already closed / clearing, don't process this frame
        if (isClosed.get()) {
            Log.v(TAG, "PoseLandmarkerHelper is closed, dropping frame")
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()


        // Drop duplicate / out-of-order frames to prevent MediaPipe filter warning
        if (frameTime <= lastDetectedTimestamp) {
            Log.v(TAG, "Dropping out-of-order frame: $frameTime <= $lastDetectedTimestamp")
            imageProxy.close()
            return
        }
        lastDetectedTimestamp = frameTime

        try {
            // 1) Prepare bitmap buffer
            if (bitmapBuffer == null ||
                            bitmapBuffer?.width != imageProxy.width ||
                            bitmapBuffer?.height != imageProxy.height
            ) {
                Log.d(TAG, "Creating new bitmap buffer: ${imageProxy.width}x${imageProxy.height}")
                bitmapBuffer =
                        Bitmap.createBitmap(
                                imageProxy.width,
                                imageProxy.height,
                                Bitmap.Config.ARGB_8888
                        )
                Log.d(TAG, "Bitmap buffer created successfully")
            }

            val bitmap = bitmapBuffer!!

            // 2) Convert YUV -> RGB using a reusable converter
            Log.v(TAG, "Getting image from ImageProxy...")
            val image = imageProxy.image
            if (image == null) {
                Log.e(TAG, "ImageProxy.image is null!")
                imageProxy.close()
                return
            }
            Log.v(TAG, "Image obtained: format=${image.format}, planes=${image.planes.size}")

            Log.v(TAG, "Converting YUV to RGB...")
            yuvToRgbConverter.yuvToRgb(image, bitmap)
            Log.v(TAG, "YUV conversion completed")

            // 3) Read rotation & dimensions BEFORE closing the proxy
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
            Log.v(TAG, "Rotation degrees: $rotationDegrees")

            // We have all info needed now; safe to close
            Log.v(TAG, "Closing ImageProxy...")
            imageProxy.close()
            Log.v(TAG, "ImageProxy closed")


            // 4) Compute rotated dimensions — width/height swap for 90°/270° rotations
            val rotatedWidth = if (rotationDegrees == 90f || rotationDegrees == 270f) bitmap.height else bitmap.width
            val rotatedHeight = if (rotationDegrees == 90f || rotationDegrees == 270f) bitmap.width else bitmap.height

            // 5) Reuse the rotated bitmap buffer — only reallocate if size actually changed
            if (rotatedBitmap == null ||
                    rotatedBitmap?.width != rotatedWidth ||
                    rotatedBitmap?.height != rotatedHeight
            ) {
                rotatedBitmap?.recycle()
                rotatedBitmap = Bitmap.createBitmap(rotatedWidth, rotatedHeight, Bitmap.Config.ARGB_8888)
                rotatedCanvas = Canvas(rotatedBitmap!!)
            }

            // 6) Draw the source bitmap into the reused buffer via matrix transform —
            // no new Bitmap allocation, just a draw call onto existing memory.
            val matrix = Matrix().apply {
                postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                postRotate(rotationDegrees)
                postTranslate(rotatedWidth / 2f, rotatedHeight / 2f)
            }
            rotatedCanvas?.drawColor(android.graphics.Color.BLACK) // clear previous frame
            rotatedCanvas?.drawBitmap(bitmap, matrix, null)

            val mpImage = BitmapImageBuilder(rotatedBitmap!!).build()

            // 7) Last safety check before calling native   // ← was mislabeled "6)" again
            if (isClosed.get()) {
                Log.v(TAG, "PoseLandmarkerHelper closed before calling detectAsync")
                return
            }

            Log.v(TAG, "Calling detectAsync with frameTime=$frameTime")
            detectAsync(mpImage, frameTime)
            Log.v(TAG, "detectAsync returned")
        } catch (t: Throwable) {
            try {
                imageProxy.close()
            } catch (closeEx: Throwable) {
                Log.e(TAG, "Failed to close ImageProxy after error", closeEx)
            }

            Log.e(TAG, "Error in detectLiveStream - Exception type: ${t.javaClass.simpleName}, Message: ${t.message}", t)
            // Print full stack trace
            t.printStackTrace()
        }
    }
    // Run pose landmark using MediaPipe Pose Landmarker API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        val t0 = SystemClock.uptimeMillis()
        poseLandmarker?.detectAsync(mpImage, frameTime)
        Log.v(TAG, "detectAsync dispatch took ${SystemClock.uptimeMillis() - t0}ms")
    }

    // Accepts the URI for a video file loaded from the user's gallery and attempts to run
    // pose landmarker inference on the video. This process will evaluate every
    // frame in the video and attach the results to a bundle that will be
    // returned.
    fun detectVideoFile(videoUri: Uri, inferenceIntervalMs: Long): ResultBundle? {
        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                    "Attempting to call detectVideoFile" + " while not using RunningMode.VIDEO"
            )
        }

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        var didErrorOccurred = false

        // Load frames from the video and run the pose landmarker.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoLengthMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null detection result
        if ((videoLengthMs == null) || (width == null) || (height == null)) return null

        // Next, we'll get one frame every frameInterval ms, then run detection on these frames.
        val resultList = mutableListOf<PoseLandmarkerResult>()
        val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

        for (i in 0..numberOfFrameToRead) {
            val timestampMs = i * inferenceIntervalMs // ms

            retriever.getFrameAtTime(
                            timestampMs * 1000, // convert from ms to micro-s
                            MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    ?.let { frame ->
                        // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                        val argb8888Frame =
                                if (frame.config == Bitmap.Config.ARGB_8888) frame
                                else frame.copy(Bitmap.Config.ARGB_8888, false)

                        // Convert the input Bitmap object to an MPImage object to run inference
                        val mpImage = BitmapImageBuilder(argb8888Frame).build()

                        // Run pose landmarker using MediaPipe Pose Landmarker API
                        poseLandmarker?.detectForVideo(mpImage, timestampMs)?.let { detectionResult
                            ->
                            resultList.add(detectionResult)
                        }
                                ?: run {
                                    didErrorOccurred = true
                                    poseLandmarkerHelperListener?.onError(
                                            "ResultBundle could not be returned" +
                                                    " in detectVideoFile"
                                    )
                                }
                    }
                    ?: run {
                        didErrorOccurred = true
                        poseLandmarkerHelperListener?.onError(
                                "Frame at specified time could not be" +
                                        " retrieved when detecting in video."
                        )
                    }
        }

        retriever.release()

        val inferenceTimePerFrameMs =
                (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

        return if (didErrorOccurred) {
            null
        } else {
            ResultBundle(resultList, inferenceTimePerFrameMs, height, width)
        }
    }

    // Accepted a Bitmap and runs pose landmarker inference on it to return
    // results back to the caller
    fun detectImage(image: Bitmap): ResultBundle? {
        if (runningMode != RunningMode.IMAGE) {
            throw IllegalArgumentException(
                    "Attempting to call detectImage" + " while not using RunningMode.IMAGE"
            )
        }

        // Inference time is the difference between the system time at the
        // start and finish of the process
        val startTime = SystemClock.uptimeMillis()

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(image).build()

        // Run pose landmarker using MediaPipe Pose Landmarker API
        poseLandmarker?.detect(mpImage)?.also { landmarkResult ->
            val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
            return ResultBundle(listOf(landmarkResult), inferenceTimeMs, image.height, image.width)
        }

        // If poseLandmarker?.detect() returns null, this is likely an error. Returning null
        // to indicate this.
        poseLandmarkerHelperListener?.onError("Pose Landmarker failed to detect.")
        return null
    }

    // Return the landmark result to this PoseLandmarkerHelper's caller
    private fun returnLivestreamResult(result: PoseLandmarkerResult, input: MPImage) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        Log.v(TAG, "Inference time: ${inferenceTime}ms")


        poseLandmarkerHelperListener?.onResults(
                ResultBundle(listOf(result), inferenceTime, input.height, input.width)
        )
    }

    // Return errors thrown during detection to this PoseLandmarkerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        poseLandmarkerHelperListener?.onError(error.message ?: "An unknown error has occurred")
    }

    companion object {
        const val TAG = "PoseLandmarkerHelper"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5F
        const val DEFAULT_NUM_POSES = 1
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
        const val MODEL_POSE_LANDMARKER_FULL = 0
        const val MODEL_POSE_LANDMARKER_LITE = 1
        const val MODEL_POSE_LANDMARKER_HEAVY = 2
    }

    data class ResultBundle(
            val results: List<PoseLandmarkerResult>,
            val inferenceTime: Long,
            val inputImageHeight: Int,
            val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}
