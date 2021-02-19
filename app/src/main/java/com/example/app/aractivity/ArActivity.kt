package com.example.app.aractivity

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.app.*
import com.example.app.arcore.ArCore
import com.example.app.filament.Filament
import com.example.app.renderer.*
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ArActivity : AppCompatActivity() {

    private val resumeBehavior: MutableStateFlow<Unit?> =
        MutableStateFlow(null)

    private val requestPermissionResultEvents: MutableSharedFlow<PermissionResultEvent> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val arCoreBehavior: MutableStateFlow<Pair<ArCore, FrameCallback>?> =
        MutableStateFlow(null)

    private val createScope = CoroutineScope(Dispatchers.Main)

    private lateinit var startScope: CoroutineScope

    private lateinit var surfaceView: SurfaceView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.example_activity)
        surfaceView = findViewById(R.id.surface_view)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(android.R.id.content)!!.windowInsetsController!!
                .also { windowInsetsController ->
                    windowInsetsController.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                    windowInsetsController.hide(WindowInsets.Type.systemBars())
                }
        } else @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility
                .or(View.SYSTEM_UI_FLAG_FULLSCREEN)       // hide status bar
                .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)  // hide navigation bar
                .or(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) // hide stat/nav bar after interaction timeout
        }

        createScope.launch {
            try {
                createUx()
            } catch (error: Throwable) {
                if (error !is UserCanceled) {
                    error.printStackTrace()
                }
            } finally {
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
                if (error !is UserCanceled) {
                    error.printStackTrace()
                }

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
        // wait for activity to resume
        resumeBehavior.filterNotNull().first()

        if (checkIfOpenGlVersionSupported(minOpenGlVersion).not()) {
            showOpenGlNotSupportedDialog(this@ArActivity)
            // finish()
            throw OpenGLVersionNotSupported
        }

        resumeBehavior.filterNotNull().first()

        // if arcore is not installed, request to install
        if (ArCoreApk
                .getInstance()
                .requestInstall(
                    this@ArActivity,
                    true,
                    ArCoreApk.InstallBehavior.REQUIRED,
                    ArCoreApk.UserMessageType.USER_ALREADY_INFORMED,
                ) == ArCoreApk.InstallStatus.INSTALL_REQUESTED
        ) {
            // make sure activity is paused before waiting for resume
            resumeBehavior.dropWhile { it != null }.filterNotNull().first()

            // check if install succeeded
            if (ArCoreApk
                    .getInstance()
                    .requestInstall(
                        this@ArActivity,
                        false,
                        ArCoreApk.InstallBehavior.REQUIRED,
                        ArCoreApk.UserMessageType.USER_ALREADY_INFORMED,
                    ) != ArCoreApk.InstallStatus.INSTALLED
            ) {
                throw UserCanceled
            }
        }

        // if permission is not granted, request permission
        if (ContextCompat.checkSelfPermission(
                this@ArActivity,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showCameraPermissionDialog(this@ArActivity)

            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequestCode,
            )

            // check if permission was granted
            if (requestPermissionResultEvents
                    .filter { it.requestCode == cameraPermissionRequestCode }
                    .first()
                    .grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            ) {
                throw UserCanceled
            }
        }

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

                    setClickListeners(modelRenderer)

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

    private fun setClickListeners(modelRenderer: ModelRenderer) {
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

    private suspend fun showCameraPermissionDialog(activity: AppCompatActivity) {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val alertDialog = AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.camera_permission_title)
                    .setMessage(R.string.camera_permission_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        continuation.resume(Unit)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        continuation.resumeWithException(UserCanceled)
                    }
                    .setCancelable(false)
                    .show()

                continuation.invokeOnCancellation { alertDialog.dismiss() }
            }
        }
    }
}
