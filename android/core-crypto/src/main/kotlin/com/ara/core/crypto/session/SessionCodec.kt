package com.ara.core.crypto.session

import com.ara.core.crypto.ratchet.RatchetState
import com.ara.core.crypto.wire.WireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Base64

/** JSON codec for persisting [RatchetState] through the SessionStore. */
object SessionCodec {

    @Serializable
    private data class SkippedDto(val dhPub: String, val n: Int, val key: String)

    @Serializable
    private data class StateDto(
        val rootKey: String,
        val dhSelfPrivate: String,
        val dhSelfPublic: String,
        val dhRemote: String?,
        val sendChainKey: String?,
        val recvChainKey: String?,
        val sendCount: Int,
        val recvCount: Int,
        val previousSendCount: Int,
        val skipped: List<SkippedDto> = emptyList(),
    )

    fun serialize(state: RatchetState): String {
        val b64 = Base64.getEncoder()
        return WireJson.json.encodeToString(
            StateDto.serializer(),
            StateDto(
                rootKey = b64.encodeToString(state.rootKey),
                dhSelfPrivate = b64.encodeToString(state.dhSelfPrivate),
                dhSelfPublic = b64.encodeToString(state.dhSelfPublic),
                dhRemote = state.dhRemote?.let(b64::encodeToString),
                sendChainKey = state.sendChainKey?.let(b64::encodeToString),
                recvChainKey = state.recvChainKey?.let(b64::encodeToString),
                sendCount = state.sendCount,
                recvCount = state.recvCount,
                previousSendCount = state.previousSendCount,
                skipped = state.skippedKeys.map { (k, v) ->
                    val dh = k.substringBefore(":")
                    val n = k.substringAfter(":").toInt()
                    SkippedDto(dh, n, b64.encodeToString(v))
                },
            )
        )
    }

    fun deserialize(json: String): RatchetState {
        val b64 = Base64.getDecoder()
        val enc = Base64.getEncoder()
        val dto = WireJson.json.decodeFromString<StateDto>(json)
        return RatchetState(
            rootKey = b64.decode(dto.rootKey),
            dhSelfPrivate = b64.decode(dto.dhSelfPrivate),
            dhSelfPublic = b64.decode(dto.dhSelfPublic),
            dhRemote = dto.dhRemote?.let(b64::decode),
            sendChainKey = dto.sendChainKey?.let(b64::decode),
            recvChainKey = dto.recvChainKey?.let(b64::decode),
            sendCount = dto.sendCount,
            recvCount = dto.recvCount,
            previousSendCount = dto.previousSendCount,
            skippedKeys = dto.skipped.associate {
                (enc.encodeToString(b64.decode(it.dhPub)) + ":" + it.n) to b64.decode(it.key)
            }.toMutableMap(),
        )
    }
}
