package com.example.myapplication

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class ModelDisplayFragment : Fragment() {

    private lateinit var surfaceView: SurfaceView
    private var filamentBridge: FilamentBridge? = null
    private var choreographer: Choreographer? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer?.postFrameCallback(this)
            filamentBridge?.render(frameTimeNanos)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_model_display, container, false)
        view.setBackgroundColor(Color.TRANSPARENT) // Ensure fragment's root view is transparent
        surfaceView = view.findViewById(R.id.model_surface_view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.setBackgroundColor(Color.TRANSPARENT) // Ensure SurfaceView itself is transparent

        // It's crucial that FilamentBridge is initialized here, after surfaceView is ready.
        filamentBridge = FilamentBridge(requireContext(), surfaceView)
        choreographer = Choreographer.getInstance()
    }

    override fun onResume() {
        super.onResume()
        choreographer?.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer?.removeFrameCallback(frameCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Important to release Filament resources
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        filamentBridge?.destroy()
        filamentBridge = null
    }

    fun startRendering() {
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        // Remove any existing callbacks to prevent duplicates before posting a new one
        choreographer?.removeFrameCallback(frameCallback)
        choreographer?.postFrameCallback(frameCallback)
        Log.d("ModelDisplayFragment", "Rendering STARTED")
    }

    fun stopRendering() {
        choreographer?.removeFrameCallback(frameCallback)
        Log.d("ModelDisplayFragment", "Rendering STOPPED")
    }

    /**
     * Destroys the current FilamentBridge instance and reinitializes it.
     * This is called when switching cameras to ensure a fresh rendering state.
     * @param onFilamentReady Callback to be invoked on the UI thread after reinitialization attempt.
     */
    fun reinitializeFilamentBridgeAndLoadModel(modelName: String, onFilamentReady: () -> Unit) {
        Log.d("ModelDisplayFragment", "Reinitializing FilamentBridge and loading model: $modelName")

        // Ensure we are on the UI thread for UI manipulations and Filament context
        activity?.runOnUiThread {
            // Stop rendering for the old bridge, if any
            choreographer?.removeFrameCallback(frameCallback)

            filamentBridge?.destroy() // Destroy existing Filament setup
            filamentBridge = null
            var reinitializationSuccess = false

            // Only proceed if the fragment is in a state where UI components are available
            if (isAdded && view != null && context != null && surfaceView.holder.surface.isValid) {
                try {
                    // It's good practice to ensure the surface is still valid and correctly formatted.
                    // The SurfaceView might have been recreated or its state changed.
                    surfaceView.setZOrderOnTop(true) // Re-apply if necessary
                    surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT) // Re-ensure format

                    filamentBridge = FilamentBridge(requireContext(), surfaceView)
                    // Load the default model. If you have different models per camera, adjust here.
                    filamentBridge?.loadModel(modelName)
                    Log.i("ModelDisplayFragment", "FilamentBridge reinitialized and model loaded successfully.")
                    reinitializationSuccess = true
                } catch (e: Exception) {
                    Log.e("ModelDisplayFragment", "Error reinitializing FilamentBridge: ${e.message}", e)
                }
            } else {
                Log.w("ModelDisplayFragment", "Fragment not in a valid state to recreate FilamentBridge. isAdded: $isAdded, view: $view, context: $context, surfaceValid: ${surfaceView.holder.surface.isValid}")
            }

            // Restart rendering for the new bridge
            // Ensure choreographer is still valid before posting.
            if (choreographer == null) {
                choreographer = Choreographer.getInstance()
            }
            // Do not automatically postFrameCallback here; let startRendering control it.
            // choreographer?.postFrameCallback(frameCallback)

            if(reinitializationSuccess) {
                startRendering() // Start rendering only if reinitialization was successful
            } else {
                Log.w("ModelDisplayFragment", "Filament reinitialization failed, not starting rendering.")
            }

            // Invoke the callback to signal completion of Filament setup
            onFilamentReady()
        }
    }

    fun updateModelTransform(transformMatrix: FloatArray) {
        filamentBridge?.updateModelTransform(transformMatrix)
    }

    companion object {
        fun newInstance() = ModelDisplayFragment()
    }
}