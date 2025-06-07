package com.example.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.view.Choreographer // Corrected import for Choreographer
// import com.google.android.filament.Filament // Keep if Filament.init() is used here.

class ModelDisplayFragment : Fragment() {

    private lateinit var surfaceView: SurfaceView
    private var filamentBridge: FilamentBridge? = null
    private var choreographer: Choreographer? = null // Type is now android.view.Choreographer

    private val frameCallback = object : Choreographer.FrameCallback { // Uses android.view.Choreographer.FrameCallback
        override fun doFrame(frameTimeNanos: Long) { // Corrected method name from onFrame to doFrame
            choreographer?.postFrameCallback(this)
            filamentBridge?.render(frameTimeNanos) // Pass frameTimeNanos
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_model_display, container, false)
        surfaceView = view.findViewById(R.id.model_surface_view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Ensure Filament is initialized - typically done in Application class or once globally.

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
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        filamentBridge?.destroy()
        filamentBridge = null
        // Consider Filament.destroy() only if it's the absolute end of Filament usage in the app.
        // For now, individual component destruction is safer.
    }

    fun updateModelPosition(x: Float, y: Float, z: Float) {
        filamentBridge?.updateModelPosition(x, y, z)
    }

    companion object {
        fun newInstance() = ModelDisplayFragment()
    }
}
