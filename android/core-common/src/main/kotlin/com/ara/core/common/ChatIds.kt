package com.ara.core.common

/**
 * Deterministic chat identifiers.
 *
 * Direct chats and "notes to self" have no server-side row — their IDs are derived
 * from the participating Ara IDs so both peers converge on the same ID without a
 * central registry (consistent with the on-device-only storage model).
 */
object ChatIds {

    private const val DIRECT_PREFIX = "dm_"
    private const val SAVED_PREFIX = "saved_"

    fun direct(a: String, b: String): String {
        val (first, second) = listOf(a, b).sorted()
        return DIRECT_PREFIX + first + "_" + second
    }

    fun savedMessages(araId: String): String = SAVED_PREFIX + araId

    fun isDirect(chatId: String): Boolean = chatId.startsWith(DIRECT_PREFIX)

    fun isSavedMessages(chatId: String, myId: String): Boolean = chatId == savedMessages(myId)

    /** Peer Ara ID of a direct chat, given my own ID. */
    fun peerOf(chatId: String, myId: String): String? =
        chatId.removePrefix(DIRECT_PREFIX).split("_")
            .firstOrNull { it != myId }
}
