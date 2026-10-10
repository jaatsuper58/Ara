package com.ara.core.crypto.session

import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.ratchet.DoubleRatchet
import com.ara.core.crypto.ratchet.RatchetHeader
import com.ara.core.crypto.ratchet.RatchetState
import com.ara.core.crypto.store.IdentityKeyStore
import com.ara.core.crypto.store.KeyBundleFetcher
import com.ara.core.crypto.store.RemoteIdentityStore
import com.ara.core.crypto.store.SessionStore
import com.ara.core.crypto.wire.RatchetHeaderDto
import com.ara.core.crypto.wire.WireMessage
import com.ara.core.crypto.x3dh.X3dh
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * End-to-end 1:1 message cipher: transparently performs the X3DH handshake on
 * the first message of a session, then runs the Double Ratchet.
 *
 * All key material stays on device; only [WireMessage] ciphertext ever leaves
 * the process boundary.
 */
class SessionCipher(
    private val sessionStore: SessionStore,
    private val identityStore: IdentityKeyStore,
    private val remoteIdentityStore: RemoteIdentityStore,
    private val bundleFetcher: KeyBundleFetcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Encrypts [plaintext] for [peerId], establishing a session on first use. */
    suspend fun encrypt(peerId: String, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): WireMessage =
        withContext(io) {
            val existing = sessionStore.load(peerId)
            val state = existing ?: establishSession(peerId)
            val (header, ciphertext, nonce) = DoubleRatchet.encrypt(state, plaintext, aad)
            sessionStore.save(peerId, state)

            // A brand-new session must carry the X3DH parameters in a PreKeyWire so the
            // responder can derive the same shared key.
            val handshake = if (existing == null) pendingHandshake.remove(peerId) else null
            if (handshake != null) {
                val identity = identityStore.identity()
                WireMessage.PreKeyWire(
                    identityX25519 = identity.x25519Public,
                    identityEd25519 = identity.ed25519Public,
                    ephemeralPublic = handshake.ephemeralPublic,
                    signedPreKeyId = handshake.signedPreKeyId,
                    oneTimePreKeyId = handshake.oneTimePreKeyId,
                    header = header.toDto(),
                    nonce = nonce,
                    ciphertext = ciphertext,
                )
            } else {
                WireMessage.RatchetWire(header.toDto(), nonce, ciphertext)
            }
        }

    /** Decrypts a wire message from [peerId]; handles prekey handshake messages transparently. */
    suspend fun decrypt(peerId: String, message: WireMessage): ByteArray = withContext(io) {
        when (message) {
            is WireMessage.PreKeyWire -> {
                val state = establishSessionFromPreKey(peerId, message)
                try {
                    val header = message.header.toRatchetHeader()
                    DoubleRatchet.decrypt(
                        state, header, message.nonce, message.ciphertext, aad,
                    )
                } finally {
                    sessionStore.save(peerId, state)
                }
            }
            is WireMessage.RatchetWire -> {
                val state = sessionStore.load(peerId)
                    ?: error("No session with $peerId — cannot decrypt ratchet message")
                try {
                    DoubleRatchet.decrypt(
                        state, message.header.toRatchetHeader(), message.nonce, message.ciphertext, aad,
                    )
                } finally {
                    sessionStore.save(peerId, state)
                }
            }
        }
    }

    suspend fun hasSession(peerId: String): Boolean = withContext(io) { sessionStore.load(peerId) != null }

    suspend fun destroySession(peerId: String) = withContext(io) { sessionStore.delete(peerId) }

    // ----- internals -----

    private suspend fun establishSession(peerId: String): RatchetState {
        val bundle = bundleFetcher.fetch(peerId)
        val identity = identityStore.identity()
        remoteIdentityStore.saveRemoteIdentity(peerId, bundle.identityX25519, bundle.identityEd25519)

        val result = X3dh.initiate(identity, bundle)
        val state = DoubleRatchet.initInitiator(result.sharedKey, bundle.signedPreKey)
        sessionStore.save(peerId, state)
        pendingHandshake[peerId] = PendingHandshake(
            ephemeralPublic = result.ephemeralPublic,
            signedPreKeyId = result.usedSignedPreKeyId,
            oneTimePreKeyId = result.usedOneTimePreKeyId,
        )
        return state
    }

    private suspend fun establishSessionFromPreKey(peerId: String, message: WireMessage.PreKeyWire): RatchetState {
        val identity = identityStore.identity()
        remoteIdentityStore.saveRemoteIdentity(peerId, message.identityX25519, message.identityEd25519)

        val signedPreKey = identityStore.currentSignedPreKey()
        val oneTimePrivate = message.oneTimePreKeyId?.let { identityStore.popOneTimePreKey(it)?.privateKey }

        val sharedKey = X3dh.respond(
            ourIdentity = identity,
            ourSignedPreKeyPrivate = signedPreKey.privateKey,
            ourOneTimePreKeyPrivate = oneTimePrivate,
            theirIdentityX25519 = message.identityX25519,
            theirEphemeralPublic = message.ephemeralPublic,
        )
        val state = DoubleRatchet.initResponder(sharedKey, signedPreKey.privateKey, signedPreKey.publicKey)
        sessionStore.save(peerId, state)
        return state
    }

    /** Parameters needed to build the outgoing PreKeyWire for [peerId]'s first message. */
    fun handshakeFor(peerId: String): PendingHandshake? = pendingHandshake[peerId]

    class PendingHandshake(
        val ephemeralPublic: ByteArray,
        val signedPreKeyId: Int,
        val oneTimePreKeyId: Int?,
    )

    private val pendingHandshake = mutableMapOf<String, PendingHandshake>()

    private fun RatchetHeader.toDto() = RatchetHeaderDto(dhPub, pn, n)

    private fun RatchetHeaderDto.toRatchetHeader() = RatchetHeader(dhPub, pn, n)

    companion object {
        private val aad: ByteArray = ByteArray(0)
    }
}
