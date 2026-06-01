package com.boxing.coach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.boxing.coach.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BoxingCoach"
        private const val REQUEST_CAMERA = 10
        private const val SAMPLE_EVERY_N_FRAMES = 10  // ~3fps
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var moveNetHelper: MoveNetHelper

    private var isRecording       = false
    private var sessionDurationMs = 15_000L
    private var selectedStance    = "orthodox"
    private var frameCount        = 0
    private val sessionKeyframes  = mutableListOf<Pair<Float, FloatArray>>()
    private var sessionStartMs    = 0L
    private var countDownTimer: CountDownTimer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasCameraPermission()) startCamera() else requestCameraPermission()
        cameraExecutor = Executors.newSingleThreadExecutor()
        moveNetHelper  = MoveNetHelper(this)
        setupUI()
    }

    private fun setupUI() {
        // Duration
        binding.btnDuration10.setOnClickListener { setDuration(10) }
        binding.btnDuration15.setOnClickListener { setDuration(15) }
        binding.btnDuration30.setOnClickListener { setDuration(30) }
        setDuration(15)

        // Stance
        binding.btnOrthodox.setOnClickListener  { setStance("orthodox") }
        binding.btnSouthpaw.setOnClickListener  { setStance("southpaw") }
        setStance("orthodox")

        // Start/Stop
        binding.btnStartStop.setOnClickListener {
            if (isRecording) stopSession() else startSession()
        }
    }

    private fun setDuration(seconds: Int) {
        sessionDurationMs = seconds * 1000L
        binding.btnDuration10.alpha = if (seconds == 10) 1f else 0.4f
        binding.btnDuration15.alpha = if (seconds == 15) 1f else 0.4f
        binding.btnDuration30.alpha = if (seconds == 30) 1f else 0.4f
    }

    private fun setStance(stance: String) {
        selectedStance = stance
        binding.btnOrthodox.alpha = if (stance == "orthodox") 1f else 0.4f
        binding.btnSouthpaw.alpha = if (stance == "southpaw") 1f else 0.4f
    }

    private fun startSession() {
        sessionKeyframes.clear()
        frameCount     = 0
        sessionStartMs = System.currentTimeMillis()
        isRecording    = true

        binding.btnStartStop.text = "STOP"
        binding.btnStartStop.setBackgroundColor(0xFFE53935.toInt())
        binding.tvStatus.text = "Recording..."
        binding.layoutControls.visibility = View.GONE

        countDownTimer = object : CountDownTimer(sessionDurationMs, 100) {
            override fun onTick(ms: Long) {
                binding.tvCountdown.text = "${(ms / 1000) + 1}s"
            }
            override fun onFinish() { stopSession() }
        }.start()
    }

    private fun stopSession() {
        isRecording = false
        countDownTimer?.cancel()

        binding.btnStartStop.text = "START"
        binding.btnStartStop.setBackgroundColor(0xFF43A047.toInt())
        binding.tvStatus.text = "Analyzing..."
        binding.tvCountdown.text = ""
        binding.btnStartStop.isEnabled = false

        val captured = sessionKeyframes.toList()
        val stance   = selectedStance
        Log.d(TAG, "Session ended: ${captured.size} keyframes, stance=$stance")

        if (captured.size < 5) {
            binding.tvStatus.text = "Too short — try again"
            binding.btnStartStop.isEnabled = true
            binding.layoutControls.visibility = View.VISIBLE
            return
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SessionAnalyzer.analyze(captured, stance)
                }
                showResults(result)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                binding.tvStatus.text = "Error: ${e.message?.take(60)}"
                binding.btnStartStop.isEnabled = true
                binding.layoutControls.visibility = View.VISIBLE
            }
        }
    }

    private fun showResults(result: SessionAnalyzer.SessionResult) {
        val intent = Intent(this, ResultsActivity::class.java).apply {
            putExtra("stance",           result.stance)
            putExtra("jab_count",        result.jabCount)
            putExtra("cross_count",      result.crossCount)
            putExtra("hook_count",       result.hookCount)
            putExtra("total_punches",    result.totalPunches)
            putExtra("overall_feedback", result.overallFeedback)
            putStringArrayListExtra("strengths",    ArrayList(result.strengths))
            putStringArrayListExtra("improvements", ArrayList(result.improvements))
            putStringArrayListExtra("punch_events", ArrayList(
                result.punchEvents.map { "%.1fs — ${it.type}  ${it.note}".format(it.timeSeconds) }
            ))
        }
        startActivity(intent)
        binding.tvStatus.text = "Ready"
        binding.btnStartStop.isEnabled = true
        binding.layoutControls.visibility = View.VISIBLE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analyzer)
            } catch (e: Exception) { Log.e(TAG, "Camera failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap  = imageProxy.toBitmap()
        val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
        imageProxy.close()
        if (!isRecording) return
        frameCount++
        if (frameCount % SAMPLE_EVERY_N_FRAMES != 0) return
        val keypoints   = moveNetHelper.detectKeypoints(rotated)
        val timeSeconds = (System.currentTimeMillis() - sessionStartMs) / 1000f
        sessionKeyframes.add(Pair(timeSeconds, keypoints))
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CAMERA && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
        moveNetHelper.close()
    }
}