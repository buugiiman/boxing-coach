package com.boxing.coach

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedList
import com.boxing.coach.BuildConfig

/**
 * Punch classifier using Claude API.
 * Maintains a 30-frame sliding window of keypoints and sends them to
 * Claude Haiku for classification every time the buffer is full.
 *
 * Replace API_KEY with your actual Anthropic API key.
 */
class PunchClassifier(context: Context) {

    companion object {
        const val WINDOW_FRAMES = 30
        const val NUM_KEYPOINTS = 17
        const val INFERENCE_STRIDE = 15   // run inference every 15 frames (~0.5s)
        const val CONFIDENCE_THRESHOLD = 0.7f
        val API_KEY = BuildConfig.ANTHROPIC_API_KEY
        val CLASS_NAMES = arrayOf("jab", "cross", "hook", "uppercut", "idle")

        private const val TAG = "PunchClassifier"
    }

    data class Result(
        val label: String,
        val classIndex: Int,
        val confidence: Float,
        val allProbs: FloatArray = FloatArray(5)
    )

    private val frameBuffer = LinkedList<FloatArray>()
    private var framesSinceInference = 0
    private var lastResult = Result("idle", 4, 1.0f)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var inferenceRunning = false

    // Normalization — must match extract_keypoints.py (hip-based)
    private fun normalizeKeypoints(kps: FloatArray): FloatArray {
        val result = kps.copyOf()
        val lhy = kps[11 * 3 + 0]; val lhx = kps[11 * 3 + 1]
        val rhy = kps[12 * 3 + 0]; val rhx = kps[12 * 3 + 1]
        val lsy = kps[5  * 3 + 0]; val lsx = kps[5  * 3 + 1]
        val rsy = kps[6  * 3 + 0]; val rsx = kps[6  * 3 + 1]

        val hipY   = (lhy + rhy) / 2f
        val hipX   = (lhx + rhx) / 2f
        val midShY = (lsy + rsy) / 2f
        val midShX = (lsx + rsx) / 2f
        val scale  = maxOf(
            Math.sqrt(((midShY - hipY).toDouble().let { it * it } +
                    (midShX - hipX).toDouble().let { it * it })).toFloat(),
            1e-6f
        )
        for (i in 0 until NUM_KEYPOINTS) {
            result[i * 3 + 0] = (kps[i * 3 + 0] - hipY) / scale
            result[i * 3 + 1] = (kps[i * 3 + 1] - hipX) / scale
        }
        return result
    }

    /**
     * Push a new frame. Returns the latest result (maybe from a previous inference).
     * Inference runs asynchronously every INFERENCE_STRIDE frames.
     */
    fun pushFrame(keypoints: FloatArray): Result {
        frameBuffer.addLast(keypoints.copyOf())
        if (frameBuffer.size > WINDOW_FRAMES) frameBuffer.removeFirst()

        framesSinceInference++

        if (frameBuffer.size == WINDOW_FRAMES &&
            framesSinceInference >= INFERENCE_STRIDE &&
            !inferenceRunning) {

            framesSinceInference = 0
            val snapshot = frameBuffer.toList()
            inferenceRunning = true

            scope.launch {
                try {
                    val result = classifyWithClaude(snapshot)
                    lastResult = result
                } catch (e: Exception) {
                    Log.e(TAG, "Inference failed", e)
                } finally {
                    inferenceRunning = false
                }
            }
        }

        return lastResult
    }

    private fun classifyWithClaude(frames: List<FloatArray>): Result {
        // Build a compact summary of key keypoints for the prompt
        // We send wrist, elbow, shoulder positions — most discriminative for punches
        val keyIndices = listOf(
            5 to "L_shoulder", 6 to "R_shoulder",
            7 to "L_elbow",    8 to "R_elbow",
            9 to "L_wrist",   10 to "R_wrist",
            0 to "nose"
        )

        // Sample every 3rd frame to keep prompt concise (10 frames)
        val sampledFrames = frames.filterIndexed { i, _ -> i % 3 == 0 }

        val sb = StringBuilder()
        sampledFrames.forEachIndexed { t, rawKps ->
            val kps = normalizeKeypoints(rawKps)
            sb.append("t$t: ")
            keyIndices.forEach { (i, name) ->
                val y    = "%.2f".format(kps[i * 3 + 0])
                val x    = "%.2f".format(kps[i * 3 + 1])
                val conf = kps[i * 3 + 2]
                if (conf > 0.2f) sb.append("$name($y,$x) ")
            }
            sb.append("\n")
        }

        val prompt = """
You are a boxing movement classifier. Analyze these normalized body keypoint sequences from a person shadowboxing.
Coordinates are normalized (centered on hips, scaled by torso height).
y increases downward, x increases rightward.
The person is southpaw (right hand leads).

Keypoint sequences (10 sampled frames):
$sb

Classify the dominant movement as exactly one of: jab, cross, hook, uppercut, idle

Rules:
- jab: right wrist extends forward rapidly (southpaw lead hand)
- cross: left wrist extends forward with shoulder rotation (southpaw power hand)
- hook: wrist moves horizontally across body in arc
- uppercut: wrist moves upward from low position
- idle: standing guard, small movements, no clear punch

Respond with ONLY a JSON object, no other text:
{"label": "...", "confidence": 0.0-1.0, "reason": "max 5 words"}
""".trimIndent()

        val requestBody = JSONObject().apply {
            put("model", "claude-haiku-4-5")
            put("max_tokens", 250)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", API_KEY)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout    = 8000

        conn.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val responseText = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            Log.e(TAG, "API error ${conn.responseCode}: $error")
            throw Exception("API ${conn.responseCode}: $error")
        }
        conn.disconnect()

        // Parse response
        val responseJson = JSONObject(responseText)
        val content = responseJson
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()

        val cleaned = content
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val result = JSONObject(cleaned)
        val label      = result.getString("label").lowercase().trim()
        val confidence = result.getDouble("confidence").toFloat()
        val reason     = result.optString("reason", "")

        Log.d(TAG, "Claude: $label (${(confidence*100).toInt()}%) — $reason")

        val classIdx = CLASS_NAMES.indexOf(label).takeIf { it >= 0 } ?: 4
        val finalLabel = if (confidence >= CONFIDENCE_THRESHOLD) label else "idle"
        val finalIdx   = if (confidence >= CONFIDENCE_THRESHOLD) classIdx else 4

        return Result(
            label      = finalLabel,
            classIndex = finalIdx,
            confidence = confidence
        )
    }

    fun reset() {
        frameBuffer.clear()
        framesSinceInference = 0
        lastResult = Result("idle", 4, 1.0f)
    }

    fun close() {
        scope.cancel()
    }
}