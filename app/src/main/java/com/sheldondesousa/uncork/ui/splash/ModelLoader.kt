package com.sheldondesousa.uncork.ui.splash

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface ModelLoadEvent {
    data class Progress(val fraction: Float?) : ModelLoadEvent
    data object Ready : ModelLoadEvent
}

fun interface ModelLoader {
    fun load(): Flow<ModelLoadEvent>
}

/**
 * Temporary development loader. This preserves the production loading contract while the
 * bundled Gemma model and MediaPipe integration are added in a later milestone.
 */
class DemoModelLoader : ModelLoader {
    override fun load(): Flow<ModelLoadEvent> = flow {
        for (step in 0..100 step 4) {
            emit(ModelLoadEvent.Progress(step / 100f))
            delay(45)
        }
        emit(ModelLoadEvent.Ready)
    }
}

