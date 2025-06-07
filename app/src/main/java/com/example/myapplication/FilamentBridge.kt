package com.example.myapplication

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import android.view.MotionEvent
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
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.Utils
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import java.nio.ByteBuffer
import java.nio.channels.Channels
import com.google.android.filament.SwapChain
import kotlin.math.tan

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
    private lateinit var cameraManipulator: Manipulator
    private var swapChain: SwapChain? = null

    companion object {
        init {
            Utils.init()
        }

        const val DEFAULT_MODEL_NAME = "tripo_convert_ec4f4f58-682d-4881-9428-319815b7fee7.glb"
        private const val TAG = "FilamentBridge"
    }

    init {
        setupFilament()
        loadModel(DEFAULT_MODEL_NAME)
    }

    private fun setupFilament() {
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()

        val cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        view.camera = camera
        view.scene = scene

        cameraManipulator = Manipulator.Builder()
            .targetPosition(0.0f, 0.0f, 0.0f)
            .upVector(0.0f, 1.0f, 0.0f)
            .zoomSpeed(0.01f)
            .orbitHomePosition(0.0f, 0.0f, 4.0f)
            .viewport(surfaceView.width, surfaceView.height)
            .build(Manipulator.Mode.ORBIT)

        try {
            val iblName = "venetian_crossroads_2k"
            val hdrAssetPath = "ibl/$iblName/${iblName}.hdr"

            // 1. Load the HDR file into a ByteBuffer using your existing readAsset function
            val buffer: ByteBuffer = readAsset(hdrAssetPath) // Ensure readAsset works correctly

            // 2. Load the HDR data from the ByteBuffer into a Filament Texture
            // Explicitly type hdrTexture to Texture? to help with type inference
            val hdrTexture: Texture? = HDRLoader.loadTexture(engine, buffer)

            if (hdrTexture != null) {
                Log.i(TAG, "Successfully loaded HDR texture from: $hdrAssetPath using ByteBuffer.")
                val indirectLight = IndirectLight.Builder()
                    .reflections(hdrTexture)
                    .build(engine)
                scene.indirectLight = indirectLight

                val skybox = Skybox.Builder()
                    .environment(hdrTexture)
                    .build(engine)
                scene.skybox = skybox
            } else {
                Log.e(TAG, "Failed to load HDR texture from: $hdrAssetPath using ByteBuffer. Using fallback color.")
                // Fallback skybox color
                val skyboxColor = floatArrayOf(0.1f, 0.15f, 0.2f, 1.0f)
                scene.skybox = Skybox.Builder().color(skyboxColor[0], skyboxColor[1], skyboxColor[2], skyboxColor[3]).build(engine)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load IBL/Skybox from HDR: ${e.message}. Using fallback color.", e)
            val skyboxColor = floatArrayOf(0.1f, 0.15f, 0.2f, 1.0f)
            scene.skybox = Skybox.Builder().color(skyboxColor[0], skyboxColor[1], skyboxColor[2], skyboxColor[3]).build(engine)
        }

        val lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.95f, 0.8f)
            .intensity(110_000.0f)
            .direction(0.28f, -0.6f, -0.75f)
            .sunAngularRadius(1.9f)
            .castShadows(true)
            .build(engine, lightEntity)
        scene.addEntity(lightEntity)

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = if (surface.isValid) {
                    engine.createSwapChain(surface)
                } else {
                    null
                }
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                if (width == 0 || height == 0) {
                    Log.w(TAG, "Viewport resized to zero dimensions, skipping update.")
                    return
                }
                view.viewport = Viewport(0, 0, width, height)
                cameraManipulator.setViewport(width, height)

                val aspect = width.toDouble() / height.toDouble()
                camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            }
        }
        uiHelper.attachTo(surfaceView)
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
                scene.addEntities(asset.entities)

                Log.i(TAG, "Loaded model: $modelPath, Entities: ${asset.entities.size}")
                focusCameraOnModel(asset)

            } ?: run {
                Log.e(TAG, "Failed to create asset from buffer for: $modelPath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model '$modelPath': ${e.message}", e)
        }
    }

    private fun focusCameraOnModel(asset: FilamentAsset) {
        val center = asset.boundingBox.center.let { Float3(it[0], it[1], it[2]) }
        val halfExtent = asset.boundingBox.halfExtent.let { Float3(it[0], it[1], it[2]) }

        val modelRadius = kotlin.math.sqrt(
            halfExtent.x * halfExtent.x +
                    halfExtent.y * halfExtent.y +
                    halfExtent.z * halfExtent.z
        )

        val fovRadians = Math.toRadians(45.0)
        val eyeDistance = (modelRadius / tan(fovRadians / 2.0)).toFloat() * 1.5f

        val eye = Float3(
            center.x,
            center.y + halfExtent.y * 0.5f,
            center.z + eyeDistance
        )
        val target = center
        val up = Float3(0.0f, 1.0f, 0.0f)

        camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            target.x.toDouble(), target.y.toDouble(), target.z.toDouble(),
            up.x.toDouble(), up.y.toDouble(), up.z.toDouble()
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
        swapChain?.let { chain ->
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                val eye = DoubleArray(3)
                val target = DoubleArray(3)
                val up = DoubleArray(3)
                cameraManipulator.getLookAt(eye, target, up)
                camera.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])

                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    fun updateModelPosition(x: Float, y: Float, z: Float) {
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
                val translationMatrix = floatArrayOf(
                    1.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 1.0f, 0.0f,
                    x,    y,    z,    1.0f
                )
                transformManager.setTransform(rootTransformInstance, translationMatrix)
            } else {
                Log.w(TAG, "Cannot update model position: asset not loaded or has no root/entities.")
            }
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cameraManipulator.grabBegin(event.x.toInt(), event.y.toInt(), false)
            }
            MotionEvent.ACTION_MOVE -> {
                cameraManipulator.grabUpdate(event.x.toInt(), event.y.toInt())
            }
            MotionEvent.ACTION_UP -> {
                cameraManipulator.grabEnd()
            }
        }
        return true
    }

    fun destroy() {
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
            // The texture used by indirectLight (hdrTexture) is typically managed by Filament
            // and should be destroyed when the IndirectLight or Engine is destroyed.
            // If hdrTexture was created by HDRLoader, it's an Engine resource.
            scene.indirectLight = null
        }
        scene.skybox?.let {
            engine.destroySkybox(it)
            // Similar to indirectLight, the texture used by skybox is managed.
            scene.skybox = null
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