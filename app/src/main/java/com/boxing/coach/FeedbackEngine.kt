package com.boxing.coach

/**
 * Rule-based feedback engine.
 * Analyzes current keypoints + last punch to generate coaching cues.
 *
 * Keypoint layout (indices into FloatArray(51)):
 *   kp[i*3+0] = y (0=top, 1=bottom)
 *   kp[i*3+1] = x (0=left, 1=right)
 *   kp[i*3+2] = confidence
 */
class FeedbackEngine {

    data class Feedback(
        val message: String,
        val priority: Int       // higher = more urgent
    )

    private var punchCount = 0
    private var lastLabel = "idle"
    private var feedbackCooldownFrames = 0

    fun update(keypoints: FloatArray, result: PunchClassifier.Result): Feedback? {
        if (result.label != "idle" && result.label != lastLabel) {
            punchCount++
        }
        lastLabel = result.label

        // Throttle feedback — don't spam every frame
        if (feedbackCooldownFrames > 0) {
            feedbackCooldownFrames--
            return null
        }

        val feedback = analyze(keypoints, result)
        if (feedback != null) {
            feedbackCooldownFrames = 45   // ~1.5s at 30fps before next cue
        }
        return feedback
    }

    private fun analyze(kps: FloatArray, result: PunchClassifier.Result): Feedback? {
        // Extract relevant keypoints
        val noseY       = kps[0  * 3 + 0]
        val lWristY     = kps[9  * 3 + 0];  val lWristConf = kps[9  * 3 + 2]
        val rWristY     = kps[10 * 3 + 0];  val rWristConf = kps[10 * 3 + 2]
        val lShoulderY  = kps[5  * 3 + 0]
        val rShoulderY  = kps[6  * 3 + 0]
        val lElbowY     = kps[7  * 3 + 0];  val lElbowConf = kps[7  * 3 + 2]
        val rElbowY     = kps[8  * 3 + 0];  val rElbowConf = kps[8  * 3 + 2]
        val lElbowX     = kps[7  * 3 + 1]
        val rElbowX     = kps[8  * 3 + 1]
        val lWristX     = kps[9  * 3 + 1]
        val rWristX     = kps[10 * 3 + 1]

        val avgShoulderY = (lShoulderY + rShoulderY) / 2f
        val chinY = noseY + 0.05f   // approximate chin position below nose

        // ── Guard check (always active, highest priority) ──────────────────
        // In image coords, smaller y = higher on screen
        // Guard is low if wrists are well below chin level
        if (result.label == "idle" || result.label == "jab" || result.label == "cross") {
            val guardThreshold = chinY + 0.08f   // wrists should be near chin
            if (lWristConf > 0.3f && lWristY > guardThreshold) {
                return Feedback("Raise your left guard", 3)
            }
            if (rWristConf > 0.3f && rWristY > guardThreshold) {
                return Feedback("Raise your right guard", 3)
            }
        }

        // ── Elbow flare check on hook ────────────────────────────────────
        // Hook elbow should be roughly parallel to shoulder (not dropped)
        if (result.label == "hook") {
            if (lElbowConf > 0.3f && lElbowY > avgShoulderY + 0.12f) {
                return Feedback("Keep your hook elbow up", 2)
            }
            if (rElbowConf > 0.3f && rElbowY > avgShoulderY + 0.12f) {
                return Feedback("Keep your hook elbow up", 2)
            }
        }

        // ── Chin tucked check ────────────────────────────────────────────
        // Nose should be roughly between shoulders laterally
        val noseX      = kps[0 * 3 + 1]
        val lShoulderX = kps[5 * 3 + 1]
        val rShoulderX = kps[6 * 3 + 1]
        if (noseX < lShoulderX - 0.05f || noseX > rShoulderX + 0.05f) {
            return Feedback("Tuck your chin", 2)
        }

        // ── Positive reinforcement (low priority) ───────────────────────
        if (result.label != "idle" && result.confidence > 0.9f) {
            return when (result.label) {
                "jab" -> Feedback("Good jab — snap it back faster", 1)
                "cross" -> Feedback("Good cross — rotate your hip", 1)
                "hook" -> Feedback("Good hook — pivot your foot", 1)
                "uppercut" -> Feedback("Good uppercut — bend your knees", 1)
                else -> null
            }
        }

        return null
    }

    fun getPunchCount() = punchCount
    fun reset() { punchCount = 0; lastLabel = "idle"; feedbackCooldownFrames = 0 }
}
