package com.ara.core.crypto.ratchet

import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.wire.B64Serializer
import kotlinx.serialization.Serializable

/**
 * Double Ratchet header: the sender's current ratchet public key, the length of
 * the previous sending chain and the message number in the current chain.
 */
@Serializable
data class RatchetHeader(
    @Serializable(with = B64Serializer::class) val dhPub: ByteArray,
    val pn: Int,
    val n: Int,
) {
    fun encode(): ByteArray {
        val out = ByteArray(32 + 4 + 4)
        System.arraycopy(dhPub, 0, out, 0, 32)
        writeInt(out, 32, pn)
        writeInt(out, 36, n)
        return out
    }

    companion object {
        fun decode(bytes: ByteArray): RatchetHeader = RatchetHeader(
            dhPub = bytes.copyOfRange(0, 32),
            pn = readInt(bytes, 32),
            n = readInt(bytes, 36),
        )

        private fun writeInt(dst: ByteArray, offset: Int, value: Int) {
            dst[offset] = (value ushr 24).toByte()
            dst[offset + 1] = (value ushr 16).toByte()
            dst[offset + 2] = (value ushr 8).toByte()
            dst[offset + 3] = value.toByte()
        }

        private fun readInt(src: ByteArray, offset: Int): Int =
            ((src[offset].toInt() and 0xFF) shl 24) or
                ((src[offset + 1].toInt() and 0xFF) shl 16) or
                ((src[offset + 2].toInt() and 0xFF) shl 8) or
                (src[offset + 3].toInt() and 0xFF)
    }
}

/** Serialisable double-ratchet session state (persisted via the SessionStore). */
@Serializable
data class RatchetState(
    @Serializable(with = B64Serializer::class) var rootKey: ByteArray,
    @Serializable(with = B64Serializer::class) var dhSelfPrivate: ByteArray,
    @Serializable(with = B64Serializer::class) var dhSelfPublic: ByteArray,
    @Serializable(with = B64Serializer::class) var dhRemote: ByteArray? = null,
    @Serializable(with = B64Serializer::class) var sendChainKey: ByteArray? = null,
    @Serializable(with = B64Serializer::class) var recvChainKey: ByteArray? = null,
    var sendCount: Int = 0,
    var recvCount: Int = 0,
    var previousSendCount: Int = 0,
    /** key = "<b64 remote dh>:<n>" -> message key. Bounded by MAX_SKIP. */
    val skippedKeys: MutableMap<String, @Serializable(with = B64Serializer::class) ByteArray> = mutableMapOf(),
)

/**
 * Signal-spec Double Ratchet providing forward secrecy and post-compromise
 * security for 1:1 sessions.
 */
object DoubleRatchet {

    private val INFO = "AraRatchetRoot".toByteArray()

    /** KDF_RK(rk, dh) -> (rk', ck) */
    fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = Crypto.hkdf(dhOutput, rootKey, INFO, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    /** KDF_CK(ck) -> (mk, ck') */
    fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        Crypto.hmacSha256(chainKey, byteArrayOf(0x01)) to
            Crypto.hmacSha256(chainKey, byteArrayOf(0x02))

    /** Initialize the sender side (initiator) — the first ratchet uses the responder's signed prekey. */
    fun initInitiator(sharedKey: ByteArray, theirSignedPreKeyPublic: ByteArray): RatchetState {
        val dhSelfPrivate = Crypto.generateX25519PrivateKey()
        val dhSelfPublic = Crypto.x25519PublicKey(dhSelfPrivate)
        val state = RatchetState(
            rootKey = sharedKey.copyOf(),
            dhSelfPrivate = dhSelfPrivate,
            dhSelfPublic = dhSelfPublic,
            dhRemote = theirSignedPreKeyPublic.copyOf(),
        )
        val (newRoot, sendChain) = kdfRootKey(
            state.rootKey,
            Crypto.x25519(dhSelfPrivate, theirSignedPreKeyPublic),
        )
        state.rootKey = newRoot
        state.sendChainKey = sendChain
        return state
    }

    /** Initialize the responder side — first DH ratchet happens lazily on first decrypt. */
    fun initResponder(sharedKey: ByteArray, ourSignedPreKeyPrivate: ByteArray, ourSignedPreKeyPublic: ByteArray): RatchetState =
        RatchetState(
            rootKey = sharedKey.copyOf(),
            dhSelfPrivate = ourSignedPreKeyPrivate.copyOf(),
            dhSelfPublic = ourSignedPreKeyPublic.copyOf(),
        )

    /** Encrypt one message; returns header + ciphertext + nonce. */
    fun encrypt(
        state: RatchetState,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        maxSkip: Int = 512,
    ): Triple<RatchetHeader, ByteArray, ByteArray> {
        val chain = state.sendChainKey
            ?: error("Cannot encrypt before receiving the first message — call initInitiator")
        val (messageKey, nextChain) = kdfChainKey(chain)
        val header = RatchetHeader(state.dhSelfPublic, state.previousSendCount, state.sendCount)
        state.sendChainKey = nextChain
        state.sendCount += 1
        require(state.skippedKeys.size < maxSkip)

        val nonce = Crypto.generateNonce()
        val fullAad = header.encode() + aad
        val ciphertext = Crypto.aesGcmEncrypt(messageKey, nonce, plaintext, fullAad)
        java.util.Arrays.fill(messageKey, 0)
        return Triple(header, ciphertext, nonce)
    }

    /** Decrypt one message, transparently handling skipped/out-of-order messages and DH ratchet steps. */
    fun decrypt(
        state: RatchetState,
        header: RatchetHeader,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray = ByteArray(0),
        maxSkip: Int = 512,
    ): ByteArray {
        val fullAad = header.encode() + aad
        skippedKey(state, header)?.let { mk ->
            return Crypto.aesGcmDecrypt(mk, nonce, ciphertext, fullAad)
        }
        if (state.dhRemote == null || !header.dhPub.contentEquals(state.dhRemote)) {
            skipMessageKeys(state, header.pn, maxSkip)
            dhRatchetStep(state, header)
        }
        skipMessageKeys(state, header.n, maxSkip)
        val (messageKey, nextChain) = kdfChainKey(
            state.recvChainKey ?: error("No receiving chain — malformed session"),
        )
        state.recvChainKey = nextChain
        state.recvCount += 1
        return Crypto.aesGcmDecrypt(messageKey, nonce, ciphertext, fullAad)
    }

    private fun skippedKey(state: RatchetState, header: RatchetHeader): ByteArray? {
        val key = skippedKeyId(header.dhPub, header.n)
        return state.skippedKeys.remove(key)
    }

    private fun skipMessageKeys(state: RatchetState, until: Int, maxSkip: Int) {
        if (state.recvChainKey == null) return
        require(state.recvCount + until <= state.recvCount + maxSkip) { "Too many skipped messages" }
        while (state.recvCount < until) {
            val (messageKey, nextChain) = kdfChainKey(state.recvChainKey!!)
            state.skippedKeys[skippedKeyId(state.dhRemote!!, state.recvCount)] = messageKey
            if (state.skippedKeys.size > maxSkip) {
                // Evict the oldest entry to bound memory; those messages are unrecoverable.
                state.skippedKeys.remove(state.skippedKeys.keys.first())
            }
            state.recvChainKey = nextChain
            state.recvCount += 1
        }
    }

    private fun dhRatchetStep(state: RatchetState, header: RatchetHeader) {
        state.previousSendCount = state.sendCount
        state.sendCount = 0
        state.recvCount = 0
        state.dhRemote = header.dhPub.copyOf()

        val (root1, recvChain) = kdfRootKey(state.rootKey, Crypto.x25519(state.dhSelfPrivate, state.dhRemote!!))
        state.rootKey = root1
        state.recvChainKey = recvChain

        state.dhSelfPrivate = Crypto.generateX25519PrivateKey()
        state.dhSelfPublic = Crypto.x25519PublicKey(state.dhSelfPrivate)
        val (root2, sendChain) = kdfRootKey(state.rootKey, Crypto.x25519(state.dhSelfPrivate, state.dhRemote!!))
        state.rootKey = root2
        state.sendChainKey = sendChain
    }

    private fun skippedKeyId(dhPub: ByteArray, n: Int): String =
        java.util.Base64.getEncoder().encodeToString(dhPub) + ":" + n
}
