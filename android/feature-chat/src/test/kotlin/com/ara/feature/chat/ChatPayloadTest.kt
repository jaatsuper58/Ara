package com.ara.feature.chat

import com.ara.feature.chat.data.ChatPayload
import com.ara.feature.chat.data.ChatPayloadJson
import com.ara.feature.chat.data.MessageReactions
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message-pipeline codec tests (spec Phase 10: pipeline coverage target). */
class ChatPayloadTest {

    @Test
    fun `text payload round trips through the wire json`() {
        val payload: ChatPayload = ChatPayload.Text(body = "hello", mentions = listOf("ARA-A", "ARA-B"))
        val json = ChatPayloadJson.json.encodeToString(payload)
        val decoded = ChatPayloadJson.json.decodeFromString(ChatPayload.serializer(), json)
        assertEquals(payload, decoded)
        assertTrue(json.contains("\"type\":\"text\""))
    }

    @Test
    fun `media payload round trips with all fields`() {
        val payload = ChatPayload.Media(
            mediaType = "IMAGE",
            fileId = "file-1",
            fileKey = "k",
            fileName = "pic.jpg",
            sizeBytes = 1234,
            mimeType = "image/jpeg",
            caption = "look",
            width = 100,
            height = 200,
        )
        val json = ChatPayloadJson.json.encodeToString<ChatPayload>(payload)
        val decoded = ChatPayloadJson.json.decodeFromString(ChatPayload.serializer(), json)
        assertEquals(payload, decoded)
    }

    @Test
    fun `sticker payload round trips`() {
        val payload: ChatPayload = ChatPayload.Sticker("🔥")
        val decoded = ChatPayloadJson.json.decodeFromString(
            ChatPayload.serializer(),
            ChatPayloadJson.json.encodeToString(payload),
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun `sender key distribution rides the same envelope format`() {
        val payload: ChatPayload = ChatPayload.SenderKeyDist(
            com.ara.core.crypto.wire.SenderKeyDistribution(
                groupId = "grp_1",
                distributionId = "d-1",
                chainKeySeed = ByteArray(32) { it.toByte() },
                iteration = 3,
            )
        )
        val decoded = ChatPayloadJson.json.decodeFromString(
            ChatPayload.serializer(),
            ChatPayloadJson.json.encodeToString(payload),
        )
        assertEquals(payload, decoded)
    }
}

class MessageReactionsTest {

    @Test
    fun `encode decode round trip`() {
        val reactions = mapOf("👍" to listOf("ARA-A", "ARA-B"), "❤️" to listOf("ARA-C"))
        val encoded = MessageReactions.encode(reactions)
        assertEquals(reactions, MessageReactions.decode(encoded))
    }

    @Test
    fun `corrupt json decodes to empty map`() {
        assertTrue(MessageReactions.decode("not json {").isEmpty())
    }

    @Test
    fun `empty map encodes to valid json`() {
        assertTrue(MessageReactions.decode(MessageReactions.encode(emptyMap())).isEmpty())
    }
}
