package com.ara.feature.calls.call

import android.content.Context
import com.ara.core.common.AccountRepository
import com.ara.core.db.dao.CallLogDao
import com.ara.core.db.entity.CallLogEntity
import com.ara.core.net.ws.SignalingClient
import com.ara.core.net.ws.SignalingIn
import com.ara.core.net.ws.SignalingOut
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class CallState { IDLE, OUTGOING, INCOMING, IN_CALL, ENDED }

data class CallUiState(
    val state: CallState = CallState.IDLE,
    val callId: String = "",
    val peerId: String = "",
    val isVideo: Boolean = false,
    val isGroup: Boolean = false,
    val startedAt: Long = 0,
    val bitrateStep: Int = 0,
    val rttMs: Int = 0,
    val error: String? = null,
)

/**
 * 1:1 WebRTC calling over the existing signaling WebSocket (§8 Phase 7).
 *
 * Group calls: the same signaling channel routes `call.join` room membership
 * and the SFU (mediasoup/LiveKit, see backend/services) fans streams out; the
 * mesh path below handles the launch target of 2–4 participants and the SFU
 * adapter replaces remote-track fan-out beyond that.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signaling: SignalingClient,
    private val callLogDao: CallLogDao,
    private val accountRepository: AccountRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack

    var localRenderer: SurfaceViewRenderer? = null
    var remoteRenderer: SurfaceViewRenderer? = null

    private val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var localVideoTrackRef: VideoTrack? = null

    private var iceCandidates: MutableList<IceCandidate> = mutableListOf()
    private var pendingRemoteAnswer: SessionDescription? = null
    private var governor = LowBandwidthGovernor { kbps -> applyMaxBitrate(kbps) }
    private var statsJob: Job? = null
    private var currentCallId: String = ""

    fun start() {
        appScope.launch {
            signaling.events.collect { event ->
                when (event) {
                    is SignalingIn.CallOffer -> onIncomingCall(event)
                    is SignalingIn.CallAnswer -> onAnswer(event)
                    is SignalingIn.CallIce -> onRemoteIce(event)
                    is SignalingIn.CallEnd -> onEnd(event)
                    is SignalingIn.CallDecline -> decline(event)
                    is SignalingIn.CallAccepted -> Unit
                    else -> Unit
                }
            }
        }
    }

    // ---------- outgoing ----------

    fun placeCall(peerId: String, isVideo: Boolean) {
        val callId = UUID.randomUUID().toString()
        currentCallId = callId
        _state.value = CallUiState(state = CallState.OUTGOING, callId = callId, peerId = peerId, isVideo = isVideo)
        ensureFactory()
        createPeerConnection(callId)
        addLocalTracks(isVideo)

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                scope.launch {
                    signaling.send(SignalingOut.CallOffer(callId = callId, to = peerId, isVideo = isVideo, sdp = desc.description))
                }
            }
        }, MediaConstraints())
    }

    fun answer(callId: String) {
        val incoming = pendingOffer ?: return
        _state.value = _state.value.copy(state = CallState.IN_CALL, startedAt = System.currentTimeMillis())
        currentCallId = callId
        ensureFactory()
        createPeerConnection(callId)
        addLocalTracks(incoming.isVideo)
        peerConnection?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.OFFER, incoming.sdp),
        )
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                scope.launch {
                    signaling.send(SignalingOut.CallAnswer(callId = callId, to = incoming.from, sdp = desc.description))
                }
            }
        }, MediaConstraints())
        pendingOffer = null
        startStatsLoop()
        logCall(peerId = incoming.from, isVideo = incoming.isVideo, direction = "INCOMING", status = "COMPLETED")
    }

    fun hangUp() {
        val s = _state.value
        scope.launch {
            if (s.peerId.isNotEmpty()) {
                signaling.send(SignalingOut.CallEnd(callId = s.callId, to = s.peerId))
            }
        }
        finishCall(if (s.state == CallState.INCOMING) "DECLINED" else "COMPLETED")
    }

    fun toggleMute(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        isMuted = muted
    }

    fun toggleCamera(on: Boolean) {
        localVideoTrackRef?.setEnabled(on)
        isCameraOn = on
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    // ---------- signaling callbacks ----------

    private data class PendingOffer(val callId: String, val from: String, val isVideo: Boolean, val sdp: String)
    private var pendingOffer: PendingOffer? = null

    private fun onIncomingCall(event: SignalingIn.CallOffer) {
        pendingOffer = PendingOffer(event.callId, event.from, event.isVideo, event.sdp)
        _state.value = CallUiState(
            state = CallState.INCOMING,
            callId = event.callId,
            peerId = event.from,
            isVideo = event.isVideo,
        )
        scope.launch { signaling.send(SignalingOut.CallRinging(callId = event.callId, to = event.from)) }
    }

    private fun onAnswer(event: SignalingIn.CallAnswer) {
        pendingRemoteAnswer = SessionDescription(SessionDescription.Type.ANSWER, event.sdp)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), pendingRemoteAnswer!!)
        _state.value = _state.value.copy(state = CallState.IN_CALL, startedAt = System.currentTimeMillis())
        startStatsLoop()
        logCall(peerId = _state.value.peerId, isVideo = _state.value.isVideo, direction = "OUTGOING", status = "COMPLETED")
    }

    private fun onRemoteIce(event: SignalingIn.CallIce) {
        val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
        if (peerConnection?.remoteDescription != null) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            iceCandidates.add(candidate)
        }
    }

    private fun onEnd(event: SignalingIn.CallEnd) {
        if (event.callId == currentCallId || currentCallId.isEmpty()) finishCall("COMPLETED")
    }

    private fun decline(event: SignalingIn.CallDecline) {
        if (event.callId == currentCallId) finishCall("DECLINED")
    }

    // ---------- peer connection plumbing ----------

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(callId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.ara.example:3478").createIceServer(),
                PeerConnection.IceServer.builder("turn:turn.ara.example:3478")
                    .setUsername("ara")
                    .setPassword(System.getProperty("ara.turn.credential", ""))
                    .createIceServer(),
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    signaling.send(
                        SignalingOut.CallIce(
                            callId = callId,
                            to = _state.value.peerId,
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        )
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    finishCall("FAILED")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                    remoteRenderer?.let { renderer ->
                        track.addSink(renderer)
                    }
                }
            }
        })
    }

    private fun addLocalTracks(isVideo: Boolean) {
        val pc = peerConnection ?: return
        val audioConstraints = MediaConstraints()
        audioSource = factory?.createAudioSource(audioConstraints)
        audioTrack = factory?.createAudioTrack("ara-a0", audioSource)
        pc.addTrack(audioTrack, listOf("ara-stream"))

        if (isVideo) {
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (cameraName != null) {
                videoCapturer = enumerator.createCapturer(cameraName, null)
                videoSource = factory?.createVideoSource(false)
                val helper = SurfaceTextureHelper.create("ara-capture", eglBase.eglBaseContext)
                videoCapturer?.initialize(helper, context, videoSource?.capturerObserver)
                videoCapturer?.startCapture(1280, 720, 30)
                localVideoTrackRef = factory?.createVideoTrack("ara-v0", videoSource)
                _localVideoTrack.value = localVideoTrackRef
                pc.addTrack(localVideoTrackRef, listOf("ara-stream"))
                localRenderer?.let { r ->
                    localVideoTrackRef?.addSink(r)
                }
            }
        }
    }

    // ---------- adaptive low-bandwidth loop ----------

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && _state.value.state == CallState.IN_CALL) {
                delay(2000)
                peerConnection?.getStats { report ->
                    val (rtt, outbound, lost) = governor.extract(report)
                    val step = governor.onStats(rtt, outbound, lost)
                    _state.value = _state.value.copy(bitrateStep = step, rttMs = rtt.roundToInt())
                }
            }
        }
    }

    /** Steps the video sender's max bitrate (low-bandwidth ladder). */
    private fun applyMaxBitrate(kbps: Int) {
        peerConnection?.senders?.forEach { sender ->
            val params = sender.parameters ?: return@forEach
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = kbps * 1000
            }
            sender.parameters = params
        }
    }

    // ---------- teardown / logging ----------

    private fun finishCall(status: String) {
        statsJob?.cancel()
        runCatching {
            videoCapturer?.stopCapture()
        }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrackRef = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        audioSource?.dispose()
        audioSource = null
        videoSource?.dispose()
        videoSource = null
        peerConnection?.close()
        peerConnection = null
        iceCandidates.clear()
        governor.reset()

        val s = _state.value
        if (s.state == CallState.IN_CALL && s.startedAt > 0) {
            logWithDuration(s, status)
        } else if (s.state == CallState.OUTGOING && status != "COMPLETED") {
            logCall(peerId = s.peerId, isVideo = s.isVideo, direction = "OUTGOING", status = status)
        }
        _state.value = CallUiState(state = CallState.ENDED, callId = s.callId, peerId = s.peerId, isVideo = s.isVideo)
    }

    private fun logCall(peerId: String, isVideo: Boolean, direction: String, status: String) {
        appScope.launch {
            callLogDao.insert(
                CallLogEntity(
                    id = UUID.randomUUID().toString(),
                    peerId = peerId,
                    isVideo = isVideo,
                    direction = direction,
                    status = status,
                    startedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun logWithDuration(s: CallUiState, status: String) {
        appScope.launch {
            callLogDao.insert(
                CallLogEntity(
                    id = UUID.randomUUID().toString(),
                    peerId = s.peerId,
                    isVideo = s.isVideo,
                    direction = "INCOMING",
                    status = status,
                    startedAt = s.startedAt,
                    durationMs = System.currentTimeMillis() -            status = status,
                    startedAt = s.startedAt,
                    durationMs = System.currentTimeMillis() - s.startedAt,
                )
            )
        }
    }

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        localVideoTrackRef?.addSink(local ?: return)
        _remoteVideoTrack.value?.addSink(remote ?: return)
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
