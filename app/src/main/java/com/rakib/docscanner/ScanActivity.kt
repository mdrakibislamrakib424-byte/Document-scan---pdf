package com.rakib.docscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.rakib.docscanner.databinding.ActivityScanBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live camera capture screen.
 *
 * Phase 1: preview + manual shutter.
 * Phase 3 addition: an optional auto-capture mode -- a background
 * ImageAnalysis stream feeds every frame to AutoCaptureController, which
 * fires the shutter itself once the page is framed, sharp, and holding
 * still. Manual capture always stays available regardless of the switch.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService
    private val autoCaptureController = AutoCaptureController()
    private val autoCaptureEnabled = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required to scan", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener { takePhoto() }

        binding.switchAutoCapture.setOnCheckedChangeListener { _, isChecked ->
            autoCaptureEnabled.set(isChecked)
            autoCaptureController.reset()
            binding.tvStatus.text = if (isChecked) {
                "Auto-capture on — point at a page"
            } else {
                "Auto-capture off — tap the shutter to scan"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy -> onFrame(imageProxy) }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(imageProxy: ImageProxy) {
        if (!autoCaptureEnabled.get() || captureInFlight.get()) {
            imageProxy.close()
            return
        }
        try {
            val gray = FrameUtils.yPlaneToGrayMat(imageProxy)
            val shouldCapture = autoCaptureController.onFrame(gray)
            runOnUiThread {
                binding.tvStatus.text = if (shouldCapture) {
                    "Capturing..."
                } else {
                    "Auto-capture on — hold the page steady in frame"
                }
            }
            if (shouldCapture && captureInFlight.compareAndSet(false, true)) {
                runOnUiThread { takePhoto() }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(cacheDir, "SCAN_$fileName.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    captureInFlight.set(false)
                    Toast.makeText(this@ScanActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri: Uri = Uri.fromFile(photoFile)
                    val intent = Intent(this@ScanActivity, ReviewActivity::class.java).apply {
                        putExtra(ReviewActivity.EXTRA_IMAGE_URI, savedUri.toString())
                    }
                    startActivity(intent)
                    finish()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
