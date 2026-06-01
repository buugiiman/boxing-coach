package com.boxing.coach

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt
import com.boxing.coach.BuildConfig


object SessionAnalyzer {

    private const val TAG = "SessionAnalyzer"
    val API_KEY = BuildConfig.ANTHROPIC_API_KEY

    data class PunchEvent(val timeSeconds: Float, val type: String, val note: String)

    data class SessionResult(
        val stance: String,
        val jabCount: Int,
        val crossCount: Int,
        val hookCount: Int,
        val totalPunches: Int,
        val punchEvents: List<PunchEvent>,
        val overallFeedback: String,
        val strengths: List<String>,
        val improvements: List<String>,
        val guardFeedback: String
    )

    // ── Public entry point ────────────────────────────────────────────────────

    fun analyze(
        keyframeData: List<Pair<Float, FloatArray>>,
        stance: String   // "orthodox" or "southpaw"
    ): SessionResult {
        val prompt = buildPrompt(keyframeData, stance)
        val responseJson = callApi(prompt)
        return parseResult(responseJson, stance)
    }


    private fun analyzeGuard(normalized: List<Pair<Float, FloatArray>>, stance: String): String {
        val leadWristIdx  = if (stance == "orthodox") 9  else 10
        val powerWristIdx = if (stance == "orthodox") 10 else 9
        val noseIdx       = 0

        var lowGuardCount    = 0
        var wideElbowCount   = 0
        var totalValidFrames = 0

        normalized.forEach { (_, kps) ->
            val noseY      = kps[noseIdx * 3]
            val noseConf   = kps[noseIdx * 3 + 2]
            val lwY        = kps[leadWristIdx  * 3];   val lwConf  = kps[leadWristIdx  * 3 + 2]
            val pwY        = kps[powerWristIdx * 3];   val pwConf  = kps[powerWristIdx * 3 + 2]
            val lElbowX    = kps[7 * 3 + 1];           val leConf  = kps[7 * 3 + 2]
            val rElbowX    = kps[8 * 3 + 1];           val reConf  = kps[8 * 3 + 2]
            val lShoulderX = kps[5 * 3 + 1]
            val rShoulderX = kps[6 * 3 + 1]

            if (noseConf < 0.2f) return@forEach
            totalValidFrames++

            // Guard low: wrists significantly below chin level (nose + 0.1)
            val chinY = noseY + 0.1f
            if (lwConf > 0.2f && lwY > chinY + 0.15f) lowGuardCount++
            if (pwConf > 0.2f && pwY > chinY + 0.15f) lowGuardCount++

            // Elbows flaring out past shoulders
            if (leConf > 0.2f && lElbowX < lShoulderX - 0.2f) wideElbowCount++
            if (reConf > 0.2f && rElbowX > rShoulderX + 0.2f) wideElbowCount++
        }

        if (totalValidFrames == 0) return "Guard analysis unavailable."

        val lowGuardPct  = lowGuardCount.toFloat()  / totalValidFrames * 100f
        val wideElbowPct = wideElbowCount.toFloat() / totalValidFrames * 100f

        val sb = StringBuilder()
        sb.append("Guard stats: low_guard=%.0f%% wide_elbows=%.0f%%\n".format(lowGuardPct, wideElbowPct))
        if (lowGuardPct  > 30f) sb.append("WARNING: hands frequently dropped below chin level\n")
        if (wideElbowPct > 25f) sb.append("WARNING: elbows frequently flaring outside shoulders\n")

        return sb.toString()
    }

    // ── Feature extraction ────────────────────────────────────────────────────

    private fun buildPrompt(
        keyframeData: List<Pair<Float, FloatArray>>,
        stance: String
    ): String {
        val normalized = keyframeData.map { (t, kps) -> Pair(t, normalizeHipBased(kps)) }
        val duration   = keyframeData.lastOrNull()?.first ?: 0f

        // Lead/power hand indices based on stance
        val leadWristIdx  = if (stance == "orthodox") 9  else 10  // L=9, R=10
        val powerWristIdx = if (stance == "orthodox") 10 else 9

        // ── 1. Wrist velocity peaks (punch candidates) ────────────────────────
        data class WristSample(val t: Float, val y: Float, val x: Float, val conf: Float)

        fun extractWrist(idx: Int): List<WristSample> =
            normalized.map { (t, kps) ->
                WristSample(t, kps[idx * 3], kps[idx * 3 + 1], kps[idx * 3 + 2])
            }

        fun velocityPeaks(samples: List<WristSample>, label: String): List<String> {
            val peaks = mutableListOf<String>()
            for (i in 1 until samples.size - 1) {
                val prev = samples[i - 1]; val cur = samples[i]; val next = samples[i + 1]
                if (cur.conf < 0.2f) continue
                val dy = cur.y - prev.y; val dx = cur.x - prev.x
                val speed = sqrt((dy * dy + dx * dx).toDouble()).toFloat()
                val nextSpeed = run {
                    val dy2 = next.y - cur.y; val dx2 = next.x - cur.x
                    sqrt((dy2 * dy2 + dx2 * dx2).toDouble()).toFloat()
                }
                // Peak: fast then decelerating
                if (speed > 0.08f && speed > nextSpeed * 1.8f) {
                    val dir = when {
                        dy < -0.05f -> "upward"
                        dy >  0.05f -> "downward"
                        dx < -0.05f -> "left"
                        dx >  0.05f -> "right"
                        else        -> "forward"
                    }
                    peaks.add("t=%.1fs %s peak speed=%.2f dir=%s".format(cur.t, label, speed, dir))
                }
            }
            return peaks
        }

        val leadWrist  = extractWrist(leadWristIdx)
        val powerWrist = extractWrist(powerWristIdx)
        val leadPeaks  = velocityPeaks(leadWrist,  if (stance == "orthodox") "L_wrist" else "R_wrist")
        val powerPeaks = velocityPeaks(powerWrist, if (stance == "orthodox") "R_wrist" else "L_wrist")

        // ── 2. Per-second motion summary ──────────────────────────────────────
        val motionSummary = StringBuilder()
        val windowSec = 1f
        var t = 0f
        while (t < duration) {
            val windowFrames = normalized.filter { (ft, _) -> ft >= t && ft < t + windowSec }
            if (windowFrames.size >= 2) {
                fun wristActivity(idx: Int): Float {
                    var total = 0f
                    for (i in 1 until windowFrames.size) {
                        val prev = windowFrames[i-1].second; val cur = windowFrames[i].second
                        if (cur[idx*3+2] < 0.2f) continue
                        val dy = cur[idx*3] - prev[idx*3]; val dx = cur[idx*3+1] - prev[idx*3+1]
                        total += sqrt((dy*dy + dx*dx).toDouble()).toFloat()
                    }
                    return total
                }
                val leadAct  = wristActivity(leadWristIdx)
                val powerAct = wristActivity(powerWristIdx)
                motionSummary.append(
                    "  t=%.0f-%.0fs: lead_wrist=%.2f power_wrist=%.2f\n"
                        .format(t, t + windowSec, leadAct, powerAct)
                )
            }
            t += windowSec
        }

        // ── 3. Shoulder rotation summary ─────────────────────────────────────
        val shoulderRotations = StringBuilder()
        for (i in 1 until normalized.size) {
            val prev = normalized[i-1].second; val cur = normalized[i].second
            val lsConf = cur[5*3+2]; val rsConf = cur[6*3+2]
            if (lsConf < 0.2f || rsConf < 0.2f) continue
            val prevWidth = prev[5*3+1] - prev[6*3+1]
            val curWidth  = cur[5*3+1]  - cur[6*3+1]
            val rotation  = curWidth - prevWidth
            if (kotlin.math.abs(rotation) > 0.05f) {
                val dir = if (rotation > 0) "open" else "close"
                shoulderRotations.append(
                    "t=%.1fs shoulder $dir Δ=%.2f\n".format(normalized[i].first, rotation)
                )
            }
        }

        val leadName  = if (stance == "orthodox") "LEFT"  else "RIGHT"
        val powerName = if (stance == "orthodox") "RIGHT" else "LEFT"
        val durationStr  = "%.0f".format(duration)
        val guardAnalysis = analyzeGuard(normalized, stance)

        return """
You are an expert boxing coach analyzing a shadowboxing session.
Athlete stance: ${stance.uppercase()} ($leadName hand leads, $powerName hand is power hand)
Session duration: ${durationStr}s

WRIST VELOCITY PEAKS (likely punch moments):
Lead wrist ($leadName):
${if (leadPeaks.isEmpty()) "  (none detected)" else leadPeaks.joinToString("\n")}
Power wrist ($powerName):
${if (powerPeaks.isEmpty()) "  (none detected)" else powerPeaks.joinToString("\n")}

PER-SECOND MOTION SUMMARY:
$motionSummary
SHOULDER ROTATIONS (cross/hook indicators):
${if (shoulderRotations.isEmpty()) "  (none detected)" else shoulderRotations}

GUARD ANALYSIS:
$guardAnalysis

PUNCH CLASSIFICATION RULES for ${stance.uppercase()}:
- JAB: $leadName wrist forward peak, minimal shoulder rotation, fast retraction
- CROSS: $powerName wrist peak WITH shoulder rotation (closing)
- HOOK: either wrist moves laterally (left/right direction) in arc
- IDLE: small movements, no clear velocity peaks

Count only CLEAR, DEFINITIVE punches (speed > 0.15). Be conservative — if unsure, classify as idle. A single ambiguous peak is NOT a punch. False positives are worse than missed punches.

Respond with ONLY valid JSON, no markdown:
{
  "jab_count": 0,
  "cross_count": 0,
  "hook_count": 0,
  "punch_events": [{"time": 0.0, "type": "jab", "note": "clean"}],
  "overall_feedback": "2-3 sentence summary",
  "strengths": ["strength 1", "strength 2"],
  "improvements": ["improvement 1", "improvement 2", "improvement 3"],
  "guard_feedback": "specific guard advice or empty string if guard was good"
}
""".trimIndent()
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private fun callApi(prompt: String): String {
        val requestBody = JSONObject().apply {
            put("model", "claude-haiku-4-5")
            put("max_tokens", 1024)
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
        conn.connectTimeout = 15000
        conn.readTimeout    = 20000

        conn.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val responseText = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            Log.e(TAG, "API error ${conn.responseCode}: $error")
            throw Exception("API ${conn.responseCode}: $error")
        }
        conn.disconnect()

        return JSONObject(responseText)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
    }

    // ── Normalization ─────────────────────────────────────────────────────────

    private fun normalizeHipBased(kps: FloatArray): FloatArray {
        val result = kps.copyOf()
        val lhy = kps[11*3]; val lhx = kps[11*3+1]
        val rhy = kps[12*3]; val rhx = kps[12*3+1]
        val lsy = kps[5*3];  val lsx = kps[5*3+1]
        val rsy = kps[6*3];  val rsx = kps[6*3+1]
        val hipY = (lhy+rhy)/2f; val hipX = (lhx+rhx)/2f
        val shY  = (lsy+rsy)/2f; val shX  = (lsx+rsx)/2f
        val scale = maxOf(sqrt(((shY-hipY)*(shY-hipY)+(shX-hipX)*(shX-hipX)).toDouble()).toFloat(), 1e-6f)
        for (i in 0 until 17) {
            result[i*3]   = (kps[i*3]   - hipY) / scale
            result[i*3+1] = (kps[i*3+1] - hipX) / scale
        }
        return result
    }

    // ── Parse result ──────────────────────────────────────────────────────────

    private fun parseResult(json: String, stance: String): SessionResult {
        Log.d(TAG, "Claude: $json")
        val obj = JSONObject(json)

        fun jsonArrayToList(key: String) = mutableListOf<String>().also { list ->
            val arr = obj.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        }

        val events = mutableListOf<PunchEvent>()
        val arr = obj.optJSONArray("punch_events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            events.add(PunchEvent(
                e.optDouble("time", 0.0).toFloat(),
                e.optString("type", "unknown"),
                e.optString("note", "")
            ))
        }

        val jab   = obj.optInt("jab_count",   0)
        val cross = obj.optInt("cross_count",  0)
        val hook  = obj.optInt("hook_count",   0)
        val guard = obj.optString("guard_feedback", "")


        return SessionResult(
            stance          = stance,
            jabCount        = jab,
            crossCount      = cross,
            hookCount       = hook,
            totalPunches    = jab + cross + hook,
            punchEvents     = events,
            overallFeedback = obj.optString("overall_feedback", ""),
            strengths       = jsonArrayToList("strengths"),
            improvements    = jsonArrayToList("improvements"),
            guardFeedback   = guard
        )
    }
}