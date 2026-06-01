package com.boxing.coach

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val stance = intent.getStringExtra("stance") ?: "unknown"
        val jabCount = intent.getIntExtra("jab_count", 0)
        val crossCount = intent.getIntExtra("cross_count", 0)
        val hookCount = intent.getIntExtra("hook_count", 0)
        val total = intent.getIntExtra("total_punches", 0)
        val feedback = intent.getStringExtra("overall_feedback") ?: ""
        val guardFeedback = intent.getStringExtra("guard_feedback") ?: ""
        if (guardFeedback.isNotEmpty()) {
            findViewById<TextView>(R.id.tvGuardFeedback).text = "🥊 $guardFeedback"
            findViewById<TextView>(R.id.tvGuardFeedback).visibility = View.VISIBLE
        }
        val strengths = intent.getStringArrayListExtra("strengths") ?: arrayListOf()
        val improvements = intent.getStringArrayListExtra("improvements") ?: arrayListOf()
        val events       = intent.getStringArrayListExtra("punch_events") ?: arrayListOf()

        // Stance badge
        val stanceColor = if (stance == "orthodox") 0xFF1565C0.toInt() else 0xFF6A1B9A.toInt()
        val tvStance = TextView(this).apply {
            text = stance.replaceFirstChar { it.uppercase() }
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(stanceColor)
            setPadding(24, 8, 24, 8)
        }
        findViewById<LinearLayout>(R.id.layoutStanceBadge).addView(tvStance)

        findViewById<TextView>(R.id.tvTotal).text   = "$total"
        findViewById<TextView>(R.id.tvJabs).text    = "$jabCount"
        findViewById<TextView>(R.id.tvCrosses).text = "$crossCount"
        findViewById<TextView>(R.id.tvHooks).text   = "$hookCount"
        findViewById<TextView>(R.id.tvFeedback).text = feedback

        fun addItems(layoutId: Int, items: List<String>, prefix: String, color: Int) {
            val layout = findViewById<LinearLayout>(layoutId)
            items.forEach { item ->
                layout.addView(TextView(this).apply {
                    text = "$prefix  $item"
                    textSize = 15f
                    setTextColor(color)
                    setPadding(0, 8, 0, 8)
                })
            }
        }

        addItems(R.id.layoutStrengths,    strengths,    "✓", 0xFF4CAF50.toInt())
        addItems(R.id.layoutImprovements, improvements, "→", 0xFFFF9800.toInt())

        val timelineLayout = findViewById<LinearLayout>(R.id.layoutTimeline)
        val btnToggle = findViewById<Button>(R.id.btnToggleTimeline)

        // Start hidden
        timelineLayout.visibility = View.GONE

        if (events.isEmpty()) {
            btnToggle.isEnabled = false
            btnToggle.text = "No Punches Detected"
        } else {
            events.forEach { e ->
                timelineLayout.addView(TextView(this).apply {
                    text = e; textSize = 13f
                    setTextColor(0xFFCCCCCC.toInt())
                    setPadding(0, 4, 0, 4)
                })
            }

            btnToggle.setOnClickListener {
                if (timelineLayout.visibility == View.GONE) {
                    timelineLayout.visibility = View.VISIBLE
                    btnToggle.text = "Hide Punch Timeline ▲"
                } else {
                    timelineLayout.visibility = View.GONE
                    btnToggle.text = "Show Punch Timeline ▼"
                }

            }
        }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}