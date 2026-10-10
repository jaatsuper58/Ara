package com.ara.feature.calls.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.feature.calls.call.CallManager
import com.ara.feature.calls.call.CallUiState
import com.ara.core.db.dao.CallLogDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import javax.inject.Inject
import javax.inject.Singleton

@HiltViewModel
class CallsViewModel @Inject constructor(
    callLogDao: CallLogDao,
) : ViewModel() {
    val callLog: StateFlow<List<com.ara.core.db.entity.CallLogEntity>> = callLogDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Shared EGL context for all SurfaceViewRenderers in the app. */
@Singleton
class EglContextHolder @Inject constructor() {
    val eglBase: EglBase = EglBase.create()
    val context: EglBase.Context get() = eglBase.eglBaseContext
}

@HiltViewModel
class InCallViewModel @Inject constructor(
    private val callManager: CallManager,
    private val eglContextHolder: EglContextHolder,
) : ViewModel() {

    val callState: StateFlow<CallUiState> = callManager.state

    val eglContext: EglBase.Context get() = eglContextHolder.context

    init {
        callManager.start()
    }

    fun placeCall(peerId: String, isVideo: Boolean) = callManager.placeCall(peerId, isVideo)

    fun answer() = callManager.answer(callManager.state.value.callId)

    fun decline() = callManager.hangUp()

    fun hangUp() = callManager.hangUp()

    fun toggleMute() {
        callManager.toggleMute(!callManager.isMuted)
    }

    fun toggleCamera() {
        callManager.toggleCamera(!callManager.isCameraOn)
    }

    fun switchCamera() = callManager.switchCamera()

    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        callManager.attachRenderers(null, renderer)
    }

    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        callManager.attachRenderers(renderer, null)
    }

    fun detachRenderers() = callManager.detachRenderers()
}
