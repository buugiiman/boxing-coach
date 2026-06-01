package com.boxing.coach

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the CameraX PreviewView.
 * Draws: skeleton, punch label, confidence, coaching text, punch count.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Skeleton edges: pairs of keypoint indices
    private val EDGES = listOf(
        0 to 1, 0 to 2,         // nose → eyes
        1 to 3, 2 to 4,         // eyes → ears
        5 to 6,                  // shoulders
        5 to 7, 7 to 9,         // left arm
        6 to 8, 8 to 10,        // right arm
        5 to 11, 6 to 12,       // torso sides
        11 to 12,                // hips
        11 to 13, 13 to 15,     // left leg
        12 to 14, 14 to 16      // right leg
    )

    private val LABEL_COLORS = mapOf(
        "jab"      to Color.rgb(50, 220, 50),
        "cross"    to Color.rgb(80, 80, 255),
        "hook"     to Color.rgb(255, 160, 30),
        "uppercut" to Color.rgb(200, 80, 255),
        "idle"     to Color.rgb(160, 160, 160)
    )

    // ── Paints ───────────────────────────────────────────────────────────
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 96f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ── State ────────────────────────────────────────────────────────────
    private var keypoints: FloatArray? = null    // size 51
    private var result: PunchClassifier.Result? = null
    private var feedbackText: String = ""
    private var punchCount: Int = 0
    private var frameWidth  = 1f
    private var frameHeight = 1f

    fun update(
        kps: FloatArray,
        res: PunchClassifier.Result,
        feedback: String,
        count: Int,
        srcWidth: Int,
        srcHeight: Int
    ) {
        keypoints    = kps
        result       = res
        feedbackText = feedback
        punchCount   = count
        frameWidth   = srcWidth.toFloat()
        frameHeight  = srcHeight.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kps = keypoints ?: return
        val res = result    ?: return

        val scaleX = width  / frameWidth
        val scaleY = height / frameHeight

        // ── Skeleton ─────────────────────────────────────────────────────
        for ((i, j) in EDGES) {
            val conf1 = kps[i * 3 + 2]
            val conf2 = kps[j * 3 + 2]
            if (conf1 < 0.2f || conf2 < 0.2f) continue

            val x1 = kps[i * 3 + 1] * frameWidth  * scaleX
            val y1 = kps[i * 3 + 0] * frameHeight * scaleY
            val x2 = kps[j * 3 + 1] * frameWidth  * scaleX
            val y2 = kps[j * 3 + 0] * frameHeight * scaleY

            bonePaint.color = edgeColor(i, j)
            canvas.drawLine(x1, y1, x2, y2, bonePaint)
        }

        for (i in 0 until 17) {
            val conf = kps[i * 3 + 2]
            if (conf < 0.2f) continue
            val cx = kps[i * 3 + 1] * frameWidth  * scaleX
            val cy = kps[i * 3 + 0] * frameHeight * scaleY
            jointPaint.color = if (i == 9 || i == 10) Color.CYAN else Color.YELLOW
            canvas.drawCircle(cx, cy, if (i == 9 || i == 10) 10f else 7f, jointPaint)
        }

        // ── Top label bar ─────────────────────────────────────────────────
        val labelColor = LABEL_COLORS[res.label] ?: Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), 130f, bgPaint)

        labelPaint.color = labelColor
        canvas.drawText(res.label.uppercase(), width / 2f, 100f, labelPaint)

        // Confidence dots
        val confText = "%.0f%%".format(res.confidence * 100)
        subLabelPaint.color = Color.argb(200, 200, 200, 200)
        canvas.drawText(confText, width / 2f, 130f, subLabelPaint)

        // ── Punch count (top-left) ────────────────────────────────────────
        canvas.drawText("Punches: $punchCount", 30f, 80f, countPaint)

        // ── Feedback bar (bottom) ─────────────────────────────────────────
        if (feedbackText.isNotEmpty()) {
            canvas.drawRect(0f, height - 90f, width.toFloat(), height.toFloat(), bgPaint)
            canvas.drawText(feedbackText, width / 2f, height - 40f, feedbackPaint)
        }
    }

    private fun edgeColor(i: Int, j: Int): Int {
        // Left side = green, right side = blue, center = white
        val leftKps  = setOf(1, 3, 5, 7, 9, 11, 13, 15)
        val rightKps = setOf(2, 4, 6, 8, 10, 12, 14, 16)
        return when {
            i in leftKps  || j in leftKps  -> Color.rgb(50, 200, 50)
            i in rightKps || j in rightKps -> Color.rgb(80, 120, 255)
            else -> Color.WHITE
        }
    }
}
