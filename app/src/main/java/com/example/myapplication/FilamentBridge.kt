package com.example.myapplication

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Utils
import com.google.android.filament.IndirectLight
import java.nio.ByteBuffer
import java.nio.channels.Channels
import com.google.android.filament.SwapChain
import com.google.android.filament.View.QualityLevel
import com.google.android.filament.View.RenderQuality
import kotlin.math.tan
import kotlin.math.sqrt // Added for vector normalization

// Helper functions for basic 3D vector math
private fun normalize(v: FloatArray): FloatArray {
    val len = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
    if (len == 0f) return floatArrayOf(0f,0f,0f) // Avoid division by zero
    return floatArrayOf(v[0]/len, v[1]/len, v[2]/len)
}

private fun cross(a: FloatArray, b: FloatArray): FloatArray {
    return floatArrayOf(
        a[1]*b[2] - a[2]*b[1],
        a[2]*b[0] - a[0]*b[2],
        a[0]*b[1] - a[1]*b[0]
    )
}

@Suppress("unused") // Keep if dot product might be used later
private fun dot(a: FloatArray, b: FloatArray): Float {
    return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
}


class FilamentBridge(private val context: Context, private val surfaceView: SurfaceView) {

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var filamentAsset: FilamentAsset? = null
    private lateinit var assetLoader: AssetLoader
    private lateinit var materialProvider: MaterialProvider
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var uiHelper: UiHelper
    private var swapChain: SwapChain? = null
    private var isDestroyed = false // Added isDestroyed flag

    companion object {
        init {
            Utils.init()
        }

        private const val TAG = "FilamentBridge"
    }

    init {
        setupFilament()
    }

    private fun setupFilament() {
        // Try creating engine with a shared EGL context, though UiHelper usually manages this.
        // This is a long shot, but worth trying if basic transparency fails.
        // val sharedContext = android.opengl.EGL14.eglGetCurrentContext()
        // engine = Engine.create(Engine.Builder().sharedContext(sharedContext).build())
        engine = Engine.create() // Keep this if the above doesn't help or causes issues.

        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.setPostProcessingEnabled(false) // Disable post-processing

        // Performance: Disable Anti-Aliasing
        view.setMultiSampleAntiAliasingOptions(View.MultiSampleAntiAliasingOptions().apply { enabled = false })
        view.setTemporalAntiAliasingOptions(View.TemporalAntiAliasingOptions().apply { enabled = false })

        // Performance: Set render quality to LOW
        val currentRenderQuality = view.renderQuality // Get the current quality settings
        currentRenderQuality.hdrColorBuffer = QualityLevel.LOW // Modify HDR color buffer quality to LOW
        view.renderQuality = currentRenderQuality // Apply the modified settings

        // Performance: Enable dynamic resolution
        val dynamicOptions = View.DynamicResolutionOptions()
        dynamicOptions.enabled = true
        view.setDynamicResolutionOptions(dynamicOptions)

        // Configure renderer clear options for transparency
        renderer.clearOptions = Renderer.ClearOptions().apply {
            // Back to fully transparent black
            clearColor = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
            clear = true
        }
        // Configure view for transparency blending
        view.blendMode = View.BlendMode.TRANSLUCENT

        val cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        view.camera = camera
        view.scene = scene

        // Adjust camera exposure for a brighter scene
        // Aperture (f-stop), Shutter speed (seconds), Sensitivity (ISO)
        // Default is f/16, 1/125s, 100 ISO. Increasing ISO makes it brighter.
        camera.setExposure(16.0f, 1.0f / 125.0f, 270.0f) // Increased ISO to 400

        // cameraManipulator = Manipulator.Builder() // Removed manipulator setup
        //     .targetPosition(0.0f, 0.0f, 0.0f)
        //     .upVector(0.0f, 1.0f, 0.0f)
        //     .zoomSpeed(0.01f)
        //     .orbitHomePosition(0.0f, 0.0f, 4.0f)
        //     .viewport(surfaceView.width, surfaceView.height)
        //     .build(Manipulator.Mode.ORBIT)

        // Setup a default indirect light with a simple color
        // This provides some ambient lighting to the scene.
        // You can adjust the intensity and color as needed.
        val indirectLightIntensity = 130_000.0f // Example intensity, increased from 100_000
        scene.indirectLight = IndirectLight.Builder()
            .intensity(indirectLightIntensity)
            // Optionally, you can set a reflection map, but for a simple default,
            // intensity alone might be sufficient or you can use a very simple cubemap if needed.
            // For now, we'll rely on the skybox color and direct light.
            .build(engine)


        val lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 1.0f, 1.0f) // Changed to neutral white
            .intensity(200_000.0f) // Reduced intensity, increased from 160_000
            .direction(0.28f, -0.6f, -0.75f) // Pointing somewhat from front-right-top
            .sunAngularRadius(1.9f)
            .castShadows(false) // Disabled shadow casting for performance
            .build(engine, lightEntity)
        scene.addEntity(lightEntity)

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.isOpaque = false // Moved before attachTo() and renderCallback setup
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = if (surface.isValid) {
                    engine.createSwapChain(surface) // Flag removed for now
                } else {
                    null
                }
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                if (isDestroyed) { // Check if FilamentBridge is already destroyed
                    Log.w(TAG, "onResized called after FilamentBridge.destroy(), skipping update.")
                    return
                }
                if (width == 0 || height == 0) {
                    Log.w(TAG, "Viewport resized to zero dimensions, skipping update.")
                    return
                }
                view.viewport = Viewport(0, 0, width, height)
                // cameraManipulator.setViewport(width, height) // Removed

                val aspect = width.toDouble() / height.toDouble()
                camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            }
        }
        uiHelper.attachTo(surfaceView) // isOpaque is now set before this call
    }

    fun loadModel(modelPath: String) {
        filamentAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
            filamentAsset = null
        }

        try {
            val buffer = readAsset(modelPath)
            filamentAsset = assetLoader.createAsset(buffer)

            filamentAsset?.let { asset ->
                resourceLoader.loadResources(asset)
                asset.releaseSourceData()
                scene.addEntities(asset.entities) // Uncommented to render the model

                Log.i(TAG, "Loaded model: $modelPath, Entities: ${asset.entities.size}")
                Log.d(TAG, "Asset BoundingBox: center=${asset.boundingBox.center.joinToString()}, halfExtent=${asset.boundingBox.halfExtent.joinToString()}")
                focusCameraOnModel(asset)

            } ?: run {
                Log.e(TAG, "Failed to create asset from buffer for: $modelPath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model '$modelPath': ${e.message}", e)
        }
    }

    private fun focusCameraOnModel(asset: FilamentAsset) {
        // Assuming the model will be translated to (0,0,0) by MainActivity
        val targetPosition = Float3(0.0f, 0.0f, 0.0f)

        val halfExtent = asset.boundingBox.halfExtent.let { Float3(it[0], it[1], it[2]) }
        val modelRadius = sqrt(
            halfExtent.x * halfExtent.x +
                    halfExtent.y * halfExtent.y +
                    halfExtent.z * halfExtent.z
        )
        Log.d(TAG, "focusCameraOnModel: modelRadius=$modelRadius")

        val fovRadians = Math.toRadians(45.0)
        // Ensure modelRadius is not zero to prevent division by zero or extremely large/small eyeDistance
        val distanceFactor = 2.0f // Increased distance factor
        val eyeDistance = if (modelRadius > 0.001f) {
            (modelRadius / tan(fovRadians / 2.0)).toFloat() * distanceFactor
        } else {
            // Default distance if model is very small or radius is zero
            3.0f // Adjusted default distance, factor applied below if needed or integrated
        }
        Log.d(TAG, "focusCameraOnModel: calculated eyeDistance for origin focus=$eyeDistance (fov=45, radius=$modelRadius)")


        // Position camera to look at the origin (targetPosition)
        val eyePosition = Float3(
            targetPosition.x,
            targetPosition.y + modelRadius * 0.1f, // Slightly elevated based on model radius, reduced elevation
            targetPosition.z + eyeDistance          // Away from origin along Z
        )
        val upVector = Float3(0.0f, 1.0f, 0.0f)
        Log.d(TAG, "focusCameraOnModel (targeting origin): eye=${eyePosition.let { "[${it.x}, ${it.y}, ${it.z}]" }}, target=${targetPosition.let { "[${it.x}, ${it.y}, ${it.z}]" }}, up=${upVector.let { "[${it.x}, ${it.y}, ${it.z}]" }}")

        camera.lookAt(
            eyePosition.x.toDouble(), eyePosition.y.toDouble(), eyePosition.z.toDouble(),
            targetPosition.x.toDouble(), targetPosition.y.toDouble(), targetPosition.z.toDouble(),
            upVector.x.toDouble(), upVector.y.toDouble(), upVector.z.toDouble()
        )
    }

    private fun readAsset(assetName: String): ByteBuffer {
        context.assets.open(assetName).use { inputStream ->
            Channels.newChannel(inputStream).use { channel ->
                val size = inputStream.available()
                val buffer = ByteBuffer.allocateDirect(size)
                channel.read(buffer)
                buffer.rewind()
                return buffer
            }
        }
    }

    fun render(frameTimeNanos: Long) {
        if (isDestroyed) { // Prevent rendering if the bridge is destroyed
            return
        }
        swapChain?.let { chain ->
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                // val eye = DoubleArray(3) // Removed camera update via manipulator
                // val target = DoubleArray(3)
                // val up = DoubleArray(3)
                // cameraManipulator.getLookAt(eye, target, up)
                // camera.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])

                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    fun updateModelTransform(transformMatrix: FloatArray) {
        filamentAsset?.let { asset ->
            if (asset.root != 0 && asset.entities.isNotEmpty()) {
                val transformManager = engine.transformManager
                var rootTransformInstance = transformManager.getInstance(asset.root)

                if (rootTransformInstance == 0) {
                    transformManager.create(asset.root)
                    rootTransformInstance = transformManager.getInstance(asset.root)
                    if (rootTransformInstance == 0) {
                        Log.e(TAG, "Failed to create or get transform instance for asset root.")
                        return
                    }
                }
                transformManager.setTransform(rootTransformInstance, transformMatrix)
            } else {
                Log.w(TAG, "Cannot update model transform: asset not loaded or has no root/entities.")
            }
        }
    }

    // fun onTouchEvent(event: MotionEvent): Boolean { // Removed onTouchEvent method
    //     when (event.action) {
    //         MotionEvent.ACTION_DOWN -> {
    //             cameraManipulator.grabBegin(event.x.toInt(), event.y.toInt(), false)
    //         }
    //         MotionEvent.ACTION_MOVE -> {
    //             cameraManipulator.grabUpdate(event.x.toInt(), event.y.toInt())
    //         }
    //         MotionEvent.ACTION_UP -> {
    //             cameraManipulator.grabEnd()
    //         }
    //     }
    //     return true
    // }

    fun destroy() {
        if (isDestroyed) {
            Log.w(TAG, "FilamentBridge.destroy() called when already destroyed or in progress.")
            return
        }
        isDestroyed = true // Set the flag at the beginning of the destruction process

        Log.d(TAG, "Destroying FilamentBridge...")
        uiHelper.detach()

        filamentAsset?.let {
            if (it.entities.isNotEmpty()) {
                scene.removeEntities(it.entities)
            }
            assetLoader.destroyAsset(it)
            filamentAsset = null
        }

        scene.indirectLight?.let {
            engine.destroyIndirectLight(it)
            scene.indirectLight = null
        }

        if (this::camera.isInitialized && camera.entity != 0) {
            if (engine.getCameraComponent(camera.entity) != null) {
                engine.destroyCameraComponent(camera.entity)
            }
            engine.destroyEntity(camera.entity)
        }

        if (this::view.isInitialized) engine.destroyView(view)
        if (this::scene.isInitialized) engine.destroyScene(scene)
        if (this::renderer.isInitialized) engine.destroyRenderer(renderer)

        // AssetLoader, MaterialProvider, ResourceLoader resources are typically managed by the engine
        // or through the assets they load/manage.
        // Explicit destruction of these loaders themselves is not usually required.

        if (this::engine.isInitialized) engine.destroy()
        Log.d(TAG, "FilamentBridge destroyed successfully.")
    }
}