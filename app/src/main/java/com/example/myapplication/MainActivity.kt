package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var modelDisplayFragment: ModelDisplayFragment? = null
    private lateinit var fragmentContainer: FrameLayout

    // Flag to prevent multiple initializations of cameraProvider
    private var isCameraProviderInitialized = false

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted, proceed to setup
                ensureCameraIsSetupAndRunning()
            } else {
                Toast.makeText(this, "Camera permission is required to use this feature.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fragmentContainer = findViewById(R.id.fragment_container)

        if (savedInstanceState == null) {
            modelDisplayFragment = ModelDisplayFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, modelDisplayFragment!!)
                .commitNow()
        } else {
            modelDisplayFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? ModelDisplayFragment
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            runningMode = RunningMode.LIVE_STREAM,
            minHandDetectionConfidence = 0.5f,
            minHandTrackingConfidence = 0.5f,
            minHandPresenceConfidence = 0.5f,
            maxNumHands = 1,
            handLandmarkerHelperListener = this
        )

        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted, proceed to setup if not already done
                ensureCameraIsSetupAndRunning()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Camera permission is needed to display live hand tracking.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // New method to consolidate camera setup logic trigger
    private fun ensureCameraIsSetupAndRunning() {
        // Ensure HandLandmarkerHelper is ready
        if (handLandmarkerHelper.isClose()) {
            handLandmarkerHelper.setupHandLandmarker()
        }

        // Setup camera provider if not already initialized
        if (!isCameraProviderInitialized && cameraProvider == null) {
            setupCameraProvider()
        } else if (cameraProvider != null) {
            // Provider exists, just (re)bind use cases.
            bindCameraUseCases()
        }
        // If cameraProvider is null but isCameraProviderInitialized is true,
        // it means setupCameraProvider was called but might be in progress.
        // The listener in setupCameraProvider will call bindCameraUseCases.
    }

    // Renamed from setupCamera and modified
    private fun setupCameraProvider() {
        if (isCameraProviderInitialized && cameraProvider != null) { // Guard against re-entry if already succeeded
            bindCameraUseCases() // If provider is already there, just bind
            return
        }
        if (isCameraProviderInitialized && cameraProvider == null) { // Guard against re-entry if in progress
            return
        }

        isCameraProviderInitialized = true // Set flag to indicate setup has been initiated

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Once provider is available, bind use cases.
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                isCameraProviderInitialized = false // Reset flag on failure to allow retry
                runOnUiThread {
                    Toast.makeText(this, "Failed to initialize camera: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera provider not available for binding use cases.")
            // Optionally, try to re-initiate setup if isCameraProviderInitialized is false
            if (!isCameraProviderInitialized) {
                 Log.d(TAG, "Attempting to re-initiate camera provider setup.")
                 setupCameraProvider()
            }
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.preview_view)
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    detectHand(imageProxy)
                }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            runOnUiThread {
                Toast.makeText(this, "Could not start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        try {
            handLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during hand detection in detectHand method: ${e.message}", e)
        }
    }

    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        runOnUiThread {
            if (resultBundle.results.isNotEmpty()) {
                val handLandmarkerResult = resultBundle.results.first()
                if (handLandmarkerResult.landmarks().isNotEmpty()) {
                    fragmentContainer.visibility = View.VISIBLE
                    val landmark = handLandmarkerResult.landmarks().first().get(12)

                    val scaleFactorX = 10.0f
                    val scaleFactorY = -10.0f
                    val scaleFactorZ = 5.0f

                    val x = (landmark.x() - 0.5f) * scaleFactorX
                    val y = (landmark.y() - 0.5f) * scaleFactorY
                    val z = (landmark.z() * scaleFactorZ) - 2.0f

                    Log.d(TAG, "Landmark 0: x=${landmark.x()}, y=${landmark.y()}, z=${landmark.z()}")
                    Log.d(TAG, "Transformed to: x=$x, y=$y, z=$z")

                    modelDisplayFragment?.updateModelPosition(x, y, z)
                } else {
                    fragmentContainer.visibility = View.GONE
                }
            } else {
                fragmentContainer.visibility = View.GONE
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Log.e(TAG, "HandLandmarkerHelper Error: $error (Code: $errorCode)")
            Toast.makeText(this, "MediaPipe Error: $error", Toast.LENGTH_SHORT).show()
            fragmentContainer.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ensureCameraIsSetupAndRunning()
        }
    }

    override fun onPause() {
        super.onPause()
        // It's good practice to unbind camera use cases in onPause to release the camera
        // and prevent issues when the app is paused.
        // cameraProvider?.unbindAll() // Consider adding this if not already handled by lifecycle or specific needs.
        // For now, matching original behavior of not explicitly unbinding here.
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarkerHelper.clearHandLandmarker()
        // cameraProvider?.unbindAll() // Ensure camera is released if not done elsewhere.
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}