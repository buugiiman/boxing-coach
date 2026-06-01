package com.boxing.coach

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs MoveNet Lightning on a single camera frame.
 *
 * Input  : Bitmap (any size — resized internally to 192×192)
 * Output : FloatArray of size 17*3 = [y0,x0,c0, y1,x1,c1, ... y16,x16,c16]
 *          Coordinates are normalized [0,1]. Confidence in [0,1].
 *
 * Keypoint indices (COCO 17-point):
 *   0=nose  1=left_eye  2=right_eye  3=left_ear  4=right_ear
 *   5=left_shoulder   6=right_shoulder
 *   7=left_elbow      8=right_elbow
 *   9=left_wrist     10=right_wrist
 *  11=left_hip       12=right_hip
 *  13=left_knee      14=right_knee
 *  15=left_ankle     16=right_ankle
 */
class MoveNetHelper(context: Context) {

    companion object {
        const val MODEL_FILE = "movenet_lightning.tflite"
        const val INPUT_SIZE = 192
        const val NUM_KEYPOINTS  = 17
        const val MIN_CONFIDENCE = 0.2f
    }

    private val interpreter: Interpreter

    // Reusable buffers — avoids GC pressure on every frame
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 1)
            .order(ByteOrder.nativeOrder())

    // Output shape: [1, 1, 17, 3]
    private val outputArray = Array(1) { Array(1) { Array(NUM_KEYPOINTS) { FloatArray(3) } } }

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(model, options)
    }

    /**
     * Run MoveNet on a bitmap. Returns FloatArray(51): [y,x,conf] × 17 keypoints.
     * Low-confidence keypoints have y=x=0.
     */
    fun detectKeypoints(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        bitmapToBuffer(scaled)

        interpreter.run(inputBuffer, outputArray)

        // Flatten (17, 3) → FloatArray(51), zero out low-confidence
        val result = FloatArray(NUM_KEYPOINTS * 3)
        for (i in 0 until NUM_KEYPOINTS) {
            val y    = outputArray[0][0][i][0]
            val x    = outputArray[0][0][i][1]
            val conf = outputArray[0][0][i][2]
            if (conf >= MIN_CONFIDENCE) {
                result[i * 3 + 0] = y
                result[i * 3 + 1] = x
                result[i * 3 + 2] = conf
            }
            // else stays 0,0,0
        }
        return result
    }

    private fun bitmapToBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            // MoveNet expects int32 RGB in range [0, 255]
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte())  // R
            inputBuffer.put(((pixel shr 8)  and 0xFF).toByte())  // G
            inputBuffer.put((pixel and 0xFF).toByte())  // B
        }
    }

    fun close() = interpreter.close()
}
