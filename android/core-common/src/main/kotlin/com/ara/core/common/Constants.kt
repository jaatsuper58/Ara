package com.ara.core.common

/** Protocol-level constants shared by client modules. */
object Constants {
    /** Wire protocol version carried in every envelope. */
    const val PROTOCOL_VERSION = 1

    /** WebSocket path on the signaling gateway. */
    const val WS_PATH = "/v1/ws"

    /** Hard cap for a single encrypted envelope payload (server rejects larger). */
    const val MAX_ENVELOPE_BYTES = 256 * 1024

    /** Chunk size for the encrypted file relay protocol. */
    const val FILE_CHUNK_SIZE = 256 * 1024

    /** Group size cap per the product spec. */
    const val GROUP_MEMBER_LIMIT = 256

    /** Disappearing message timer options (seconds). 0 = off. */
    val DISAPPEARING_OPTIONS_SECONDS = listOf(0L, 3600L, 86400L, 7 * 86400L)

    /** Media retention options (days). -1 = forever. */
    val MEDIA_RETENTION_OPTIONS_DAYS = listOf(-1, 30, 7, 3)

    /** Skip window for the double ratchet. */
    const val RATCHET_MAX_SKIP = 512

    /** Number of one-time prekeys generated per batch. */
    const val ONE_TIME_PREKEY_BATCH = 100
}
