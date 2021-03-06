package com.example.app.renderer

import android.content.Context
import com.example.app.*
import com.example.app.arcore.ArCore
import com.example.app.filament.Filament
import com.google.android.filament.gltfio.FilamentAsset
import com.google.ar.core.Frame
import com.google.ar.core.Point
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer

class ModelRenderer(
    private val context: Context,
    private val arCore: ArCore,
    private val filament: Filament,
    initialPos: ModelEvent.Move,
    private val initialModel: ModelResource
) {

    val modelEvents: MutableSharedFlow<ModelEvent> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val doFrameEvents: MutableSharedFlow<Frame> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var translation: V3 = v3Origin
    private var rotate: Float = 0f
    private var scale: Float = 1f

    private var lastX = initialPos.x
    private var lastY = initialPos.y

    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.Main)

    private var filamentAsset: FilamentAsset? = null

    fun renderModel(resource: ModelResource) {
        destroyAssets()
        coroutineScope.launch {
            filamentAsset = load3DModel(resource)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun load3DModel(resource: ModelResource) = withContext(Dispatchers.IO) {
        context.assets
            .open(resource.res)
            .use { input ->
                val bytes = ByteArray(input.available())
                input.read(bytes)
                val byteBuffer = ByteBuffer.wrap(bytes)
                filament.assetLoader.createAssetFromBinary(byteBuffer)!!
            }
    }.also {
        filament.resourceLoader.loadResources(it)
    }

    init {
        arCore.frame
            .hitTest(initialPos.x, initialPos.y)
            .maxByOrNull {
                it.trackable is Point
            }?.let {
                V3(it.hitPose.translation)
            }?.let {
                translation = it
            }

        coroutineScope.launch {

            filamentAsset = load3DModel(initialModel)

            launch {
                modelEvents.mapNotNull { modelEvent ->
                    (modelEvent as? ModelEvent.Move)
                        ?.let {
                            lastX += modelEvent.x
                            lastY += modelEvent.y
                            arCore.frame
                                .hitTest(lastX, lastY)
                                .maxByOrNull {
                                    it.trackable is Point
                                }
                        }?.let {
                            V3(it.hitPose.translation)
                        }
                }.collect {
                    translation = it
                }
            }

            launch {
                modelEvents.collect { modelEvent ->
                    when (modelEvent) {
                        is ModelEvent.Update ->
                            Pair((rotate + modelEvent.rotate).clampToTau, scale * modelEvent.scale)
                        else ->
                            Pair(rotate, scale)
                    }.let { (r, s) ->
                        rotate = r
                        scale = s
                    }
                }
            }

            launch {
                doFrameEvents.filter {
                    filamentAsset != null
                }.map {
                    filamentAsset!!
                }.collect { asset ->
                    val entity = filament.engine.transformManager.getInstance(asset.root)
                    val localTransform = m4Identity()
                        .translate(translation.x, translation.y, translation.z)
                        .rotate(rotate.toDegrees, 0f, 1f, 0f)
                        .scale(scale, scale, scale)
                        .floatArray
                    filament.scene.addEntities(asset.entities)
                    filament.engine.transformManager.setTransform(entity, localTransform)
                }
            }
        }
    }

    fun destroy() {
        destroyAssets()
        coroutineScope.cancel()
    }

    private fun destroyAssets() {
        if (filamentAsset != null) {
            filament.scene.removeEntities(filamentAsset!!.entities)
            filament.assetLoader.destroyAsset(filamentAsset!!)
            filamentAsset = null
        }
    }

}
