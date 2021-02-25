package com.example.app.aractivity

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.app.*
import com.example.app.arcore.ArCore
import com.example.app.filament.Filament
import com.example.app.renderer.*
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

class ArActivity : AppCompatActivity() {

    private val createScope = CoroutineScope(Dispatchers.Main)
    private lateinit var startScope: CoroutineScope

    private val resumeBehavior: MutableStateFlow<Unit?> =
        MutableStateFlow(null)
    private val requestPermissionResultEvents: MutableSharedFlow<PermissionResultEvent> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val arCoreBehavior: MutableStateFlow<Pair<ArCore, FrameCallback>?> =
        MutableStateFlow(null)

    private lateinit var surfaceView: SurfaceView
    private lateinit var filament: Filament
    private lateinit var arCore: ArCore
    private lateinit var lightRenderer: LightRenderer
    private var renderers = mutableListOf<ModelRenderer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.example_activity)
        surfaceView = findViewById(R.id.surface_view)
        createScope.launch {
            try {
                createAR()
            } catch (error: Throwable) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        createScope.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        startScope = CoroutineScope(Dispatchers.Main)
        startScope.launch {
            try {
                startAR()
            } catch (error: Throwable) {
                finish()
            }
        }
    }

    override fun onStop() {
        startScope.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeBehavior.tryEmit(Unit)
    }

    override fun onPause() {
        super.onPause()
        resumeBehavior.tryEmit(null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestPermissionResultEvents.tryEmit(PermissionResultEvent(requestCode, grantResults))
    }

    private suspend fun createAR() {
        resumeBehavior.filterNotNull().first()

        if (hasPermission()) {
            requestPermission()
            val gotPermission = requestPermissionResultEvents
                .first()
                .grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (gotPermission)
                return
        }

        try {

            filament = Filament(this@ArActivity, surfaceView)
            arCore = ArCore(this@ArActivity, filament, surfaceView)
            lightRenderer = LightRenderer(this@ArActivity, arCore.filament)

            val doFrame = fun(frame: Frame) {

                val hasTrackedState = frame.getUpdatedTrackables(Plane::class.java)
                    .any { it.trackingState == TrackingState.TRACKING }

                if (hasTrackedState)
                    findViewById<View>(R.id.loader).visibility = View.GONE

                renderers.forEach {
                    it.doFrameEvents.tryEmit(frame)
                }

                lightRenderer.doFrame(frame)
            }

            val frameCallback = FrameCallback(arCore, doFrame)

            arCoreBehavior.emit(Pair(arCore, frameCallback))

            setClickListeners(arCore)

            awaitCancellation()
        } finally {
            destroyAR()
        }

    }

    private fun destroyAR() {
        if (this::filament.isInitialized)
            filament.destroy()
        if (this::arCore.isInitialized)
            arCore.destroy()
        renderers.forEach {
            it.destroy()
        }
    }

    private suspend fun startAR() {
        val (arCore, frameCallback) = arCoreBehavior.filterNotNull().first()
        try {
            arCore.session.resume()
            frameCallback.start()
            awaitCancellation()
        } finally {
            frameCallback.stop()
            arCore.session.pause()
        }
    }

    private fun setClickListeners(arCore: ArCore) {

        findViewById<Button>(R.id.remove).setOnClickListener {
            renderers.lastOrNull()?.destroy()
            renderers.removeLastOrNull()
        }

        findViewById<Button>(R.id.place).setOnClickListener {
            val centerX = surfaceView.width / 2f
            val centerY = surfaceView.height / 2f
            val event = ModelRenderer.ModelEvent.Move(x = centerX, y = centerY)

            val modelRenderer = ModelRenderer(this@ArActivity, arCore, arCore.filament, event)
            renderers.add(modelRenderer)
        }

        findViewById<Button>(R.id.topMove).setOnClickListener {
            val move = ModelRenderer.ModelEvent.Move(x = 0f, y = 10f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(move)
        }

        findViewById<Button>(R.id.bottomMove).setOnClickListener {
            val move = ModelRenderer.ModelEvent.Move(x = 0f, y = -10f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(move)
        }

        findViewById<Button>(R.id.rightMove).setOnClickListener {
            val move = ModelRenderer.ModelEvent.Move(x = 0f, y = 0f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(move)
        }

        findViewById<Button>(R.id.leftMove).setOnClickListener {
            val move = ModelRenderer.ModelEvent.Move(x = 0f, y = 0f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(move)
        }

        findViewById<Button>(R.id.rotateMinusButton).setOnClickListener {
            val rotation = ModelRenderer.ModelEvent.Update((-10f).toRadians, 1f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(rotation)
        }

        findViewById<Button>(R.id.rotatePlusButton).setOnClickListener {
            val rotation = ModelRenderer.ModelEvent.Update((10f).toRadians, 1f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(rotation)
        }

        findViewById<Button>(R.id.scalePlusButton).setOnClickListener {
            val scaleUpdate = ModelRenderer.ModelEvent.Update(0f, 1.1f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(scaleUpdate)
        }

        findViewById<Button>(R.id.scaleMinusButton).setOnClickListener {
            val scaleUpdate = ModelRenderer.ModelEvent.Update(0f, 0.9f)
            renderers.lastOrNull()?.modelEvents?.tryEmit(scaleUpdate)
        }
    }

}