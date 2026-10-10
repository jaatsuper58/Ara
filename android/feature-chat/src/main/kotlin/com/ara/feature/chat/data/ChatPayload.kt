package com.ara.feature.chat.data

import com.ara.core.crypto.wire.SenderKeyDistribution
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Application-level payloads, carried as plaintext INSIDE the E2EE envelope.
 * The server only ever sees the encrypted wrapper.
 */
@Serializable
sealed class ChatPayload {

    @Serializable
    @SerialName("text")
    data class Text(val body: String, val mentions: List<String> = emptyList()) : ChatPayload()

    @Serializable
    @SerialName("edit")
    data class Edit(val targetId: String, val body: String) : ChatPayload()

    @Serializable
    @SerialName("delete")
    data class Delete(val targetId: String) : ChatPayload()

    @Serializable
    @SerialName("reaction")
    data class Reaction(val targetId: String, val emoji: String, val remove: Boolean = false) : ChatPayload()

    @Serializable
    @SerialName("media")
    data class Media(
        val mediaType: String, // IMAGE | VIDEO | FILE | AUDIO | LOCATION | CONTACT
        val fileId: String? = null,
        val fileKey: String? = null, // base64 AES key for the encrypted chunks (E2EE files)
        val fileName: String? = null,
        val sizeBytes: Long = 0,
        val mimeType: String? = null,
        val caption: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val contactAraId: String? = null,
    ) : ChatPayload()

    @Serializable
    @SerialName("sticker")
    data class Sticker(val emoji: String) : ChatPayload()

    /** Sender-key distribution for groups, tunneled through a pairwise 1:1 session. */
    @Serializable
    @SerialName("skd")
    data class SenderKeyDist(val distribution: SenderKeyDistribution) : ChatPayload()
}

object ChatPayloadJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
