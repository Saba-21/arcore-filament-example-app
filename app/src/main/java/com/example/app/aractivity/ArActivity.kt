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

    private val resumeBehavior: MutableStateFlow<Unit?> =
        MutableStateFlow(null)

    private val requestPermissionResultEvents: MutableSharedFlow<PermissionResultEvent> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val arCoreBehavior: MutableStateFlow<Pair<ArCore, FrameCallback>?> =
        MutableStateFlow(null)

    private val createScope = CoroutineScope(Dispatchers.Main)

    private lateinit var startScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.example_activity)
        createScope.launch {
            try {
                createUx()
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
                startUx()
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

    private suspend fun createUx() {
        resumeBehavior.filterNotNull().first()

        if (hasPermission()) {
            requestPermission()
            val gotPermission = requestPermissionResultEvents
                .first()
                .grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (gotPermission)
                return
        }

        val surfaceView = findViewById<SurfaceView>(R.id.surface_view)
        val filament = Filament(this@ArActivity, surfaceView)

        try {
            val arCore = ArCore(this@ArActivity, filament, surfaceView)

            try {
                val lightRenderer = LightRenderer(this@ArActivity, arCore.filament)
                val modelRenderer = ModelRenderer(this@ArActivity, arCore, arCore.filament)

                try {
                    val doFrame = { frame: Frame ->
                        val hasTrackedState = frame.getUpdatedTrackables(Plane::class.java)
                            .any { it.trackingState == TrackingState.TRACKING }

                        if (hasTrackedState) {
                            findViewById<View>(R.id.loader).visibility = View.GONE
                        }

                        lightRenderer.doFrame(frame)
                        modelRenderer.doFrame(frame)
                    }

                    val frameCallback = FrameCallback(arCore, doFrame)

                    arCoreBehavior.emit(Pair(arCore, frameCallback))

                    setClickListeners(modelRenderer, surfaceView)

                    awaitCancellation()
                } finally {
                    modelRenderer.destroy()
                }
            } finally {
                arCore.destroy()
            }
        } finally {
            filament.destroy()
        }
    }

    private suspend fun startUx() {
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

    private fun setClickListeners(modelRenderer: ModelRenderer, surfaceView: SurfaceView) {
        findViewById<Button>(R.id.place).setOnClickListener {
            val centerX = surfaceView.width / 2f
            val centerY = surfaceView.height / 2f
            val x = centerX / surfaceView.width
            val y = centerY / surfaceView.height
            val event = ModelRenderer.ModelEvent.Move(x = x, y = y)
            modelRenderer.modelEvents.tryEmit(event)
        }

        findViewById<Button>(R.id.rotateMinusButton).setOnClickListener {
            val rotation = ModelRenderer.ModelEvent.Update((-10f).toRadians, 1f)
            modelRenderer.modelEvents.tryEmit(rotation)
        }

        findViewById<Button>(R.id.rotatePlusButton).setOnClickListener {
            val rotation = ModelRenderer.ModelEvent.Update((10f).toRadians, 1f)
            modelRenderer.modelEvents.tryEmit(rotation)
        }

        var scale = 0f

        findViewById<Button>(R.id.scalePlusButton).setOnClickListener {
            if (scale < 0)
                scale = 0.01f
            else
                scale += 0.01f
            val rotation = ModelRenderer.ModelEvent.Update(0f, 1f + scale)
            modelRenderer.modelEvents.tryEmit(rotation)
        }

        findViewById<Button>(R.id.scaleMinusButton).setOnClickListener {
            if (scale > 0)
                scale = -0.01f
            else
                scale -= 0.01f
            val rotation = ModelRenderer.ModelEvent.Update(0f, 1f + scale)
            modelRenderer.modelEvents.tryEmit(rotation)
        }
    }

}