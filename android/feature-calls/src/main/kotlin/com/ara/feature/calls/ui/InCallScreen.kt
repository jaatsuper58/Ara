package com.ara.feature.calls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.feature.calls.call.CallState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * §7.6 — In-call screen: full-screen remote video (1:1), controls
 * (mute/camera/speaker/hang-up), live bandwidth status from the
 * low-bandwidth governor.
 */
@Composable
fun InCallScreen(onHangUp: () -> Unit, viewModel: InCallViewModel = hiltViewModel()) {
    val state by viewModel.callState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            viewModel.detachRenderers()
            com.ara.feature.calls.call.CallService.stop(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when (state.state) {
                CallState.OUTGOING -> "Calling ${state.peerId}…"
                CallState.INCOMING -> "${state.peerId} is calling"
                CallState.IN_CALL -> "${state.peerId} · connected"
                CallState.ENDED -> "Call ended"
                CallState.IDLE -> ""
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.state == CallState.IN_CALL) {
            Text(
                "adaptive bitrate step ${state.bitrateStep} · rtt ${state.rttMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Remote video / audio placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .padding(vertical = 16.dp),
        ) {
            if (state.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(viewModel.eglContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            viewModel.attachRemoteRenderer(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Voice call", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.state == CallState.INCOMING) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FilledIconButton(
                    onClick = { viewModel.decline() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White) }
                FilledIconButton(
                    onClick = { viewModel.answer() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Default.Mic, contentDescription = "Answer", tint = Color.White) }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                FilledIconButton(onClick = { viewModel.toggleMute() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.MicOff, contentDescription = "Mute")
                }
                if (state.isVideo) {
                    FilledIconButton(onClick = { viewModel.toggleCamera() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.VideocamOff, contentDescription = "Camera")
                    }
                    FilledIconButton(onClick = { viewModel.switchCamera() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Videocam, contentDescription = "Switch camera")
                    }
                }
                FilledIconButton(
                    onClick = { viewModel.hangUp(); onHangUp() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Default.CallEnd, contentDescription = "Hang up", tint = Color.White) }
            }
        }
    }
}
