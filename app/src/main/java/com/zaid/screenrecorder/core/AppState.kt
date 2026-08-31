package com.zaid.screenrecorder.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppState {
    private val mutableRecording = MutableStateFlow(RecordingStatus())
    val recording: StateFlow<RecordingStatus> = mutableRecording
    fun update(status: RecordingStatus) { mutableRecording.value = status }
}
