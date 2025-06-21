package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PixelFormat // Added import
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
import android.util.Size // Import for Size
import android.widget.Button // Import Button
import kotlin.rem

class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var modelDisplayFragment: ModelDisplayFragment? = null
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var switchCameraButton: Button
    private var currentLensFacing = CameraSelector.LENS_FACING_FRONT // Default to front camera
    private var currentModelName = "21.glb"

    // Scaling and positioning constants
    companion object {
        private const val TAG = "MainActivity"

        // Unified scale factors for consistency across all models
        private const val UNIFIED_SCALE_FACTOR_FRONT = 25.0f
        private const val UNIFIED_SCALE_FACTOR_BACK = 40.0f
        private const val RING_FINGER_DIAMETER_RATIO = 0.22f // Tuned for ring finger

        // Sensitivity for Z-depth based modulation of the scale factor
        // 0.0f = no dynamic adjustment based on Z-depth (uses base factors directly)
        // Positive value = makes ring relatively larger when hand is further, smaller when closer
        // Negative value = makes ring relatively smaller when hand is further, larger when closer
        // Tune this value to achieve the desired dynamic scaling effect.
        private const val Z_MODULATION_SENSITIVITY = -0.1f

        // Position scaling factors
        private const val POSITION_SCALE_FACTOR_FRONT = 0.4f
        private const val POSITION_SCALE_FACTOR_BACK = 0.6f // Increased for back camera

        // Y-offset for model placement
        private const val Y_OFFSET_FRONT = -0.25f
        private const val Y_OFFSET_BACK = 0f // Increased for back camera

        // Depth scale factor (kept common for now)
        private const val DEPTH_SCALE_FACTOR = -0.1f
    }

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

        // Attempt to make the window support translucency
        window.setFormat(PixelFormat.TRANSLUCENT) // Add this line

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

        // Restore camera and hand landmarking
        cameraExecutor = Executors.newSingleThreadExecutor()

        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            runningMode = RunningMode.LIVE_STREAM,
            minHandDetectionConfidence = 0.5f,
            minHandTrackingConfidence = 0.5f,
            minHandPresenceConfidence = 0.5f,
            maxNumHands = 1,
            currentDelegate = HandLandmarkerHelper.DELEGATE_GPU, // Using GPU delegate for potentially better performance
            handLandmarkerHelperListener = this
        )

        requestCameraPermission()

        switchCameraButton = findViewById(R.id.button_switch_camera)
        switchCameraButton.setOnClickListener {
            switchCamera()
        }

        findViewById<Button>(R.id.button_model_21).setOnClickListener {
            loadModel("21.glb")
        }
        findViewById<Button>(R.id.button_model_23).setOnClickListener {
            loadModel("23.glb")
        }
        findViewById<Button>(R.id.button_model_27).setOnClickListener {
            loadModel("27.glb")
        }

    }

    private fun loadModel(modelName: String) {
        currentModelName = modelName
        fragmentContainer.visibility = View.GONE // Hide model immediately

        // Reinitialize FilamentBridge in the fragment and load the new model
        modelDisplayFragment?.reinitializeFilamentBridgeAndLoadModel(currentModelName) {
            Log.d(TAG, "Filament reinitialization complete for model $currentModelName. Binding camera use cases.")
            // Rebind camera use cases to ensure the preview is running
            bindCameraUseCases()
        }
    }

    private fun switchCamera() {
        Log.d(TAG, "Switching camera. Current: ${if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) "Front" else "Back"}")
        fragmentContainer.visibility = View.GONE // Hide model immediately

        // Reinitialize FilamentBridge in the fragment and bind camera use cases in the callback
        modelDisplayFragment?.reinitializeFilamentBridgeAndLoadModel(currentModelName) {
            Log.d(TAG, "Filament reinitialization complete. Binding camera use cases.")
            currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            // Rebind camera use cases with the new lens facing AFTER filament is ready
            bindCameraUseCases()
        }
    }

    private fun requestCameraPermission() {
        // Restore camera and hand landmarking
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
        // Restore camera and hand landmarking
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
    }

    // Renamed from setupCamera and modified
    private fun setupCameraProvider() {
        // Restore camera and hand landmarking
        if (isCameraProviderInitialized && cameraProvider != null) { // Guard against re-entry if already succeeded
            bindCameraUseCases() // If provider is already there, just bind
            return
        }
        // Simplified guard: If initialization has been started, don't restart it.
        // The listener will eventually set cameraProvider or handle failure.
        if (isCameraProviderInitialized) {
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
        // Restore camera and hand landmarking
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
            .requireLensFacing(currentLensFacing // Use currentLensFacing variable
            )
            .build()

        val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.preview_view)
        previewView.implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE // Added this line
        val preview = Preview.Builder()
            // .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Removed deprecated call; CameraX will attempt to select a suitable aspect ratio.
            .setTargetRotation(previewView.display.rotation) // Depends on previewView
            .build()
            .also { // Depends on previewView
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageAnalyzer = ImageAnalysis.Builder()
            // .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Removed deprecated call; CameraX will attempt to select a suitable aspect ratio.
            .setTargetRotation(previewView.display.rotation) // Depends on previewView
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
        // Restore camera and hand landmarking
        try {
            handLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = (currentLensFacing == CameraSelector.LENS_FACING_FRONT) // Dynamically set based on currentLensFacing
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during hand detection in detectHand method: ${e.message}", e)
        }
    }

    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        runOnUiThread {
            if (resultBundle.results.isNotEmpty()) {
                val handLandmarkerResult = resultBundle.results.first()

                // 2. Check for sufficient landmarks for the ring finger
                if (handLandmarkerResult.landmarks().isNotEmpty() &&
                    handLandmarkerResult.landmarks().first().size > 15 &&  // Need up to index 15 (RING_FINGER_DIP)
                    handLandmarkerResult.worldLandmarks().isNotEmpty() &&
                    handLandmarkerResult.worldLandmarks().first().size >= HandLandmarkerHelper.MIN_WORLD_LANDMARKS_FOR_DIAMETER) {

                    fragmentContainer.visibility = View.VISIBLE // Show when hand is detected

                    val landmarks = handLandmarkerResult.landmarks().first()
                    val worldLandmarks = handLandmarkerResult.worldLandmarks().first()

                    // 3. Use screen landmarks for positioning and world landmarks for orientation
                    val pip_screen = landmarks[14] // RING_FINGER_PIP
                    val dip_screen = landmarks[15] // RING_FINGER_DIP

                    // Anchor point is the midpoint of the ring segment on screen
                    val anchorXNorm = (pip_screen.x() + dip_screen.x()) / 2f
                    val anchorYNorm = (pip_screen.y() + dip_screen.y()) / 2f
                    val anchorZNorm = (pip_screen.z() + dip_screen.z()) / 2f

                    // Adjust these factors to control sensitivity and range of movement
                    val positionScaleFactor: Float
                    val yOffset: Float
                    val baseModelSpecificScaleFactor: Float

                    if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                        positionScaleFactor = POSITION_SCALE_FACTOR_FRONT
                        yOffset = Y_OFFSET_FRONT
                        baseModelSpecificScaleFactor = UNIFIED_SCALE_FACTOR_FRONT
                    } else { // Back camera
                        positionScaleFactor = POSITION_SCALE_FACTOR_BACK
                        yOffset = Y_OFFSET_BACK
                        baseModelSpecificScaleFactor = UNIFIED_SCALE_FACTOR_BACK
                    }

                    val depthScaleFactor = DEPTH_SCALE_FACTOR // Common for now

                    val imageAspectRatio = resultBundle.inputImageWidth.toFloat() / resultBundle.inputImageHeight.toFloat()
                    val landmarkZ = anchorZNorm // Use anchor's Z for depth calculation

                    // Dynamically adjust the modelSpecificScaleFactor based on landmarkZ
                    val zModulation = 1.0f + (landmarkZ - 0.5f) * Z_MODULATION_SENSITIVITY
                    val dynamicModelSpecificScaleFactor = baseModelSpecificScaleFactor * zModulation.coerceIn(0.8f, 1.2f)

                    // Convert normalized landmark coordinates to world coordinates
                    val x = (anchorXNorm - 0.5f) * positionScaleFactor * imageAspectRatio
                    val y = ((0.5f - anchorYNorm) * positionScaleFactor) + yOffset
                    val z = landmarkZ * depthScaleFactor

                    // Calculate finger direction vector using WORLD LANDMARKS for stable 3D orientation
                    val pip_world = worldLandmarks[14]
                    val dip_world = worldLandmarks[15]
                    var fingerDirX = dip_world.x() - pip_world.x()
                    var fingerDirY = dip_world.y() - pip_world.y()
                    var fingerDirZ = dip_world.z() - pip_world.z()

                    // Normalize the direction vector
                    val len = kotlin.math.sqrt(fingerDirX*fingerDirX + fingerDirY*fingerDirY + fingerDirZ*fingerDirZ)
                    if (len > 0.0001f) {
                        fingerDirX /= len
                        fingerDirY /= len
                        fingerDirZ /= len
                    } else {
                        fingerDirX = 0f
                        fingerDirY = 1f // Default to pointing up
                        fingerDirZ = 0f
                    }

                    // 4. Estimate finger diameter using world landmarks and a ratio for the ring finger
                    val estimatedFingerDiameter = HandLandmarkerHelper.estimateFingerDiameter(worldLandmarks, RING_FINGER_DIAMETER_RATIO)

                    // 5. Apply a unified scale across all models
                    val dynamicScale = estimatedFingerDiameter * dynamicModelSpecificScaleFactor

                    val transformMatrix = calculateTransformMatrix(x, y, z, dynamicScale, fingerDirX, fingerDirY, fingerDirZ, currentModelName)

                    Log.d(TAG, "Ring Finger. PIP: (x=${pip_screen.x()}, y=${pip_screen.y()}, z=${landmarkZ}), Est. Diameter: ${"%.4f".format(estimatedFingerDiameter)}m, Final DynScale: ${"%.4f".format(dynamicScale)}")
                    modelDisplayFragment?.updateModelTransform(transformMatrix)
                } else {
                    Log.d(TAG, "No hand or not enough landmarks detected for ring finger, hiding model and stopping rendering.")
                    fragmentContainer.visibility = View.GONE // Hide if no landmarks or not enough landmarks
                }
            } else {
                Log.d(TAG, "No results from HandLandmarker, hiding model and stopping rendering.")
                fragmentContainer.visibility = View.GONE // Hide if no results
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        // Restore camera and hand landmarking
        runOnUiThread {
            Log.e(TAG, "HandLandmarkerHelper Error: $error (Code: $errorCode)")
            Toast.makeText(this, "MediaPipe Error: $error", Toast.LENGTH_SHORT).show()
            fragmentContainer.visibility = View.GONE // Hide on error
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore camera and hand landmarking
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ensureCameraIsSetupAndRunning()
        }
    }

    override fun onPause() {
        super.onPause()
        // Restore camera and hand landmarking
        // It's good practice to unbind camera use cases in onPause to release the camera
        // and prevent issues when the app is paused.
        // cameraProvider?.unbindAll() // Consider adding this if not already handled by lifecycle or specific needs.
        // For now, matching original behavior of not explicitly unbinding here.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Restore camera and hand landmarking
        cameraExecutor.shutdown()
        handLandmarkerHelper.clearHandLandmarker()
        cameraProvider?.unbindAll() // Ensure camera is released
    }

    private fun calculateTransformMatrix(x: Float, y: Float, z: Float, dynamicScale: Float, fingerDirX: Float, fingerDirY: Float, fingerDirZ: Float, modelName: String): FloatArray {
        // The finger direction vector, which will align with the ring's axis (Y-axis).
        val modelY_aligned = normalize(floatArrayOf(fingerDirX, fingerDirY, fingerDirZ))

        // The desired "up" for the ring (Z-axis) is to point towards the world's Y-axis.
        val upVector = floatArrayOf(0f, 1f, 0f)

        // Make the model's Z-axis orthogonal to its Y-axis (the finger direction)
        // using Gram-Schmidt orthogonalization.
        val dotProd = dot(upVector, modelY_aligned)
        val projectedZ = floatArrayOf(
            upVector[0] - dotProd * modelY_aligned[0],
            upVector[1] - dotProd * modelY_aligned[1],
            upVector[2] - dotProd * modelY_aligned[2]
        )

        var modelZ_aligned: FloatArray
        val lenSq = projectedZ[0] * projectedZ[0] + projectedZ[1] * projectedZ[1] + projectedZ[2] * projectedZ[2]
        if (lenSq < 0.00001f) {
            // modelY_aligned is collinear with upVector (finger pointing up/down).
            // Pick an alternative "up" vector.
            val alternativeUp = floatArrayOf(1f, 0f, 0f) // World X-axis
            val dotProd2 = dot(alternativeUp, modelY_aligned)
            val projectedZ2 = floatArrayOf(
                alternativeUp[0] - dotProd2 * modelY_aligned[0],
                alternativeUp[1] - dotProd2 * modelY_aligned[1],
                alternativeUp[2] - dotProd2 * modelY_aligned[2]
            )
            modelZ_aligned = normalize(projectedZ2)
        } else {
            modelZ_aligned = normalize(projectedZ)
        }

        // The model's X-axis is the cross product of Y and Z, completing the orthonormal basis.
        val modelX_aligned = normalize(cross(modelY_aligned, modelZ_aligned))

        // Base axes
        val baseX = modelY_aligned
        val baseY = modelX_aligned
        val baseZ = modelZ_aligned

        return when (modelName) {
            "21.glb" -> calculateTransformMatrixFor21(x, y, z, dynamicScale, baseX, baseY, baseZ)
            "23.glb" -> calculateTransformMatrixFor23(x, y, z, dynamicScale, baseX, baseY, baseZ)
            "27.glb" -> calculateTransformMatrixFor27(x, y, z, dynamicScale, baseX, baseY, baseZ)
            else -> FloatArray(16) { if (it % 5 == 0) 1f else 0f } // Identity matrix - "do nothing"
        }
    }


    private fun calculateTransformMatrixFor21(
        x: Float, y: Float, z: Float,
        dynamicScale: Float,
        baseX: FloatArray, baseY: FloatArray, baseZ: FloatArray
    ): FloatArray {
        // Helper for vector scaling
        fun scale(v: FloatArray, s: Float): FloatArray = floatArrayOf(v[0] * s, v[1] * s, v[2] * s)
        // Helper for vector addition
        fun add(v1: FloatArray, v2: FloatArray): FloatArray = floatArrayOf(v1[0] + v2[0], v1[1] + v2[1], v1[2] + v2[2])

        // 1. Initial Pitch around baseX
        val pitchRadians = -10f * (Math.PI.toFloat() / 180f)
        val cosP = kotlin.math.cos(pitchRadians)
        val sinP = kotlin.math.sin(pitchRadians)
        val y1 = add(scale(baseY, cosP), scale(baseZ, -sinP))
        val z1 = add(scale(baseY, sinP), scale(baseZ, cosP))
        val x1 = baseX

        // 2. Flip X axis (aligns ring along finger)
        val x2 = scale(x1, -1f)
        val y2 = y1
        val z2 = z1

        // 3. Yaw around y2 (turns ring sideways)
        val yawRadians = 5f * (Math.PI.toFloat() / 180f)
        val cosY = kotlin.math.cos(yawRadians)
        val sinY = kotlin.math.sin(yawRadians)
        val x3 = add(scale(x2, cosY), scale(z2, sinY))
        val z3 = add(scale(x2, -sinY), scale(z2, cosY))
        val y3 = y2

        // 4. Roll around z3 to tilt gem towards camera
        val rollRadians = 20f * (Math.PI.toFloat() / 180f) // Increased roll to make effect visible
        val cosR = kotlin.math.cos(rollRadians)
        val sinR = kotlin.math.sin(rollRadians)
        val x4 = add(scale(x3, cosR), scale(y3, -sinR))
        val y4 = add(scale(x3, sinR), scale(y3, cosR))
        val z4 = z3

        // 5. Flip Z axis (model-specific adjustment)
        val z5 = scale(z4, -1f)
        val x5 = x4
        val y5 = y4

        // 6. Final pitch around x5 to make gem face camera
        val finalPitchRad = 54f * (Math.PI.toFloat() / 180f)
        val cosC = kotlin.math.cos(finalPitchRad)
        val sinC = kotlin.math.sin(finalPitchRad)
        val y6 = add(scale(y5, cosC), scale(z5, -sinC))
        val z6 = add(scale(y5, sinC), scale(z5, cosC))
        val x6 = x5

        // Final axes for the matrix
        val finalX = x6
        val finalY = y6
        val finalZ = z6

        val s = dynamicScale
        return floatArrayOf(
            s * finalX[0], s * finalX[1], s * finalX[2], 0f,
            s * finalY[0], s * finalY[1], s * finalY[2], 0f,
            s * finalZ[0], s * finalZ[1], s * finalZ[2], 0f,
            x, y, z, 1f
        )
    }

    private fun calculateTransformMatrixFor23(x: Float, y: Float, z: Float, dynamicScale: Float, baseX: FloatArray, baseY: FloatArray, baseZ: FloatArray): FloatArray {
        // Helper for vector scaling
        fun scale(v: FloatArray, s: Float): FloatArray = floatArrayOf(v[0] * s, v[1] * s, v[2] * s)
        // Helper for vector addition
        fun add(v1: FloatArray, v2: FloatArray): FloatArray = floatArrayOf(v1[0] + v2[0], v1[1] + v2[1], v1[2] + v2[2])

        // 1. Initial Pitch around baseX
        val pitchRadians = -10f * (Math.PI.toFloat() / 180f)
        val cosP = kotlin.math.cos(pitchRadians)
        val sinP = kotlin.math.sin(pitchRadians)
        val y1 = add(scale(baseY, cosP), scale(baseZ, -sinP))
        val z1 = add(scale(baseY, sinP), scale(baseZ, cosP))
        val x1 = baseX

        // 2. Flip X axis (aligns ring along finger)
        val x2 = scale(x1, -1f)
        val y2 = y1
        val z2 = z1

        // 3. Yaw around y2 (turns ring sideways)
        val yawRadians = 20f * (Math.PI.toFloat() / 180f) // Adjusted from -10 to rotate left
        val cosY = kotlin.math.cos(yawRadians)
        val sinY = kotlin.math.sin(yawRadians)
        val x3 = add(scale(x2, cosY), scale(z2, sinY))
        val z3 = add(scale(x2, -sinY), scale(z2, cosY))
        val y3 = y2

        // 4. Roll around z3 to tilt gem towards camera
        val rollRadians = 20f * (Math.PI.toFloat() / 180f) // Increased roll to make effect visible
        val cosR = kotlin.math.cos(rollRadians)
        val sinR = kotlin.math.sin(rollRadians)
        val x4 = add(scale(x3, cosR), scale(y3, -sinR))
        val y4 = add(scale(x3, sinR), scale(y3, cosR))
        val z4 = z3

        // 5. Flip Z axis (model-specific adjustment)
        val z5 = scale(z4, -1.1f)
        val x5 = x4
        val y5 = y4

        // 6. Final pitch around x5 to make gem face camera
        val finalPitchRad = 170f * (Math.PI.toFloat() / 180f) // Adjusted to face camera
        val cosC = kotlin.math.cos(finalPitchRad)
        val sinC = kotlin.math.sin(finalPitchRad)
        val y6 = add(scale(y5, cosC), scale(z5, -sinC))
        val z6 = add(scale(y5, sinC), scale(z5, cosC))
        val x6 = x5

        // Final axes for the matrix
        val finalX = x6
        val finalY = y6
        val finalZ = z6

        val s = dynamicScale
        return floatArrayOf(
            s * finalX[0], s * finalX[1], s * finalX[2], 0f,
            s * finalY[0], s * finalY[1], s * finalY[2], 0f,
            s * finalZ[0], s * finalZ[1], s * finalZ[2], 0f,
            x, y, z, 1f
        )
    }

    private fun calculateTransformMatrixFor27(x: Float, y: Float, z: Float, dynamicScale: Float, baseX: FloatArray, baseY: FloatArray, baseZ: FloatArray): FloatArray {
        // Helper for vector scaling
        fun scale(v: FloatArray, s: Float): FloatArray = floatArrayOf(v[0] * s, v[1] * s, v[2] * s)
        // Helper for vector addition
        fun add(v1: FloatArray, v2: FloatArray): FloatArray = floatArrayOf(v1[0] + v2[0], v1[1] + v2[1], v1[2] + v2[2])

        // 1. Initial Pitch around baseX
        val pitchRadians = -10f * (Math.PI.toFloat() / 180f)
        val cosP = kotlin.math.cos(pitchRadians)
        val sinP = kotlin.math.sin(pitchRadians)
        val y1 = add(scale(baseY, cosP), scale(baseZ, -sinP))
        val z1 = add(scale(baseY, sinP), scale(baseZ, cosP))
        val x1 = baseX

        // 2. Flip X axis (aligns ring along finger)
        val x2 = scale(x1, -1f)
        val y2 = y1
        val z2 = z1

        // 3. Yaw around y2 (turns ring sideways)
        val yawRadians = 80f * (Math.PI.toFloat() / 180f)
        val cosY = kotlin.math.cos(yawRadians)
        val sinY = kotlin.math.sin(yawRadians)
        val x3 = add(scale(x2, cosY), scale(z2, sinY))
        val z3 = add(scale(x2, -sinY), scale(z2, cosY))
        val y3 = y2

        // 4. Roll around z3 to tilt gem towards camera
        val rollRadians = 20f * (Math.PI.toFloat() / 180f) // Increased roll to make effect visible
        val cosR = kotlin.math.cos(rollRadians)
        val sinR = kotlin.math.sin(rollRadians)
        val x4 = add(scale(x3, cosR), scale(y3, -sinR))
        val y4 = add(scale(x3, sinR), scale(y3, cosR))
        val z4 = z3

        // 5. Flip Z axis (model-specific adjustment)
        val z5 = scale(z4, -1f)
        val x5 = x4
        val y5 = y4

        // 6. Final pitch around x5 to make gem face camera
        val finalPitchRad = 120f * (Math.PI.toFloat() / 180f) // Adjusted to face camera
        val cosC = kotlin.math.cos(finalPitchRad)
        val sinC = kotlin.math.sin(finalPitchRad)
        val y6 = add(scale(y5, cosC), scale(z5, -sinC))
        val z6 = add(scale(y5, sinC), scale(z5, cosC))
        val x6 = x5

        // Final axes for the matrix
        val finalX = x6
        val finalY = y6
        val finalZ = z6

        val s = dynamicScale * 1.2f // Increase scale for model 27
        return floatArrayOf(
            s * finalX[0], s * finalX[1], s * finalX[2], 0f,
            s * finalY[0], s * finalY[1], s * finalY[2], 0f,
            s * finalZ[0], s * finalZ[1], s * finalZ[2], 0f,
            x, y, z, 1f
        )
    }


    private fun dot(a: FloatArray, b: FloatArray): Float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    private fun normalize(v: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len == 0f) return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }
}