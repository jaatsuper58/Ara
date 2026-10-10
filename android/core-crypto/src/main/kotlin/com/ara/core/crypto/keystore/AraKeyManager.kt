package com.ara.core.crypto.keystore

import com.ara.core.crypto.keys.IdentityKeyPair
import com.ara.core.crypto.keys.KeyBundle
import com.ara.core.crypto.keys.OneTimePreKey
import com.ara.core.crypto.keys.SignedPreKey
import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.store.IdentityKeyStore
import com.ara.core.common.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import com.ara.core.crypto.keys.OneTimePreKeyUpload

/**
 * On-device identity/prekey management.
 *
 * Private keys are generated locally, wrapped by the Android Keystore via
 * [SecureBlobStore], and never leave the device — only public halves are
 * uploaded as the prekey bundle.
 */
@Singleton
class AraKeyManager @Inject constructor(
    private val blobStore: SecureBlobStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : IdentityKeyStore {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class IdentityDto(
        val xPriv: String, val xPub: String, val ePriv: String, val ePub: String,
    )

    @Serializable
    private data class SignedPreKeyDto(val id: Int, val priv: String, val pub: String, val sig: String)

    @Serializable
    private data class OneTimeKeysDto(val keys: List<OneTimeDto> = emptyList())

    @Serializable
    private data class OneTimeDto(val id: Int, val priv: String, val pub: String)

    override suspend fun identity(): IdentityKeyPair = withContext(io) {
        mutex.withLock {
            blobStore.getSecret(IDENTITY_BLOB)?.let { return@withLock decodeIdentity(it) }
            val identity = IdentityKeyPair.generate()
            blobStore.putSecret(IDENTITY_BLOB, json.encodeToString(encodeIdentity(identity)).toByteArray())
            identity
        }
    }

    override suspend fun currentSignedPreKey(): SignedPreKey = withContext(io) {
        mutex.withLock {
            blobStore.getSecret(SPK_BLOB)?.let { return@withLock decodeSpk(it) }
            val spk = createSignedPreKey(identity(), nextSpkId())
            blobStore.putSecret(SPK_BLOB, json.encodeToString(encodeSpk(spk)).toByteArray())
            spk
        }
    }

    override suspend fun rotateSignedPreKey(): SignedPreKey = withContext(io) {
        mutex.withLock {
            val spk = createSignedPreKey(identity(), nextSpkId())
            blobStore.putSecret(SPK_BLOB, json.encodeToString(encodeSpk(spk)).toByteArray())
            spk
        }
    }

    override suspend fun popOneTimePreKey(id: Int): OneTimePreKey? = withContext(io) {
        mutex.withLock {
            val all = loadOneTimeKeys().toMutableList()
            val removed = all.firstOrNull { it.id == id } ?: return@withContext null
            all.removeAll { it.id == id }
            saveOneTimeKeys(all)
            removed
        }
    }

    override suspend fun oneTimePreKeyCount(): Int = withContext(io) { loadOneTimeKeys().size }

    override suspend fun addOneTimePreKeys(count: Int): List<OneTimePreKey> = withContext(io) {
        mutex.withLock {
            val all = loadOneTimeKeys().toMutableList()
            val nextId = (all.maxOfOrNull { it.id } ?: 0) + 1
            val added = (0 until count).map { index ->
                val priv = Crypto.generateX25519PrivateKey()
                OneTimePreKey(
                    id = nextId + index,
                    privateKey = priv,
                    publicKey = Crypto.x25519PublicKey(priv),
                )
            }
            all.addAll(added)
            saveOneTimeKeys(all)
            added
        }
    }

    override suspend fun allOneTimePreKeys(): List<OneTimePreKey> = withContext(io) { loadOneTimeKeys() }

    /** Public bundle for upload — private halves never included. */
    suspend fun publicBundle(araId: String): KeyBundle {
        val identity = identity()
        val spk = currentSignedPreKey()
        val opks = allOneTimePreKeys()
        return KeyBundle(
            araId = araId,
            identityX25519 = identity.x25519Public,
            identityEd25519 = identity.ed25519Public,
            signedPreKeyId = spk.id,
            signedPreKey = spk.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = opks.firstOrNull()?.id,
            oneTimePreKey = opks.firstOrNull()?.publicKey,
        )
    }

    /** Public halves of ALL one-time prekeys for (re)upload — server pops one per fetch. */
    suspend fun oneTimePreKeysForUpload(): List<OneTimePreKeyUpload> =
        allOneTimePreKeys().map {
            OneTimePreKeyUpload(id = it.id, publicKey = it.publicKey)
        }

    private suspend fun createSignedPreKey(identity: IdentityKeyPair, id: Int): SignedPreKey {
        val priv = Crypto.generateX25519PrivateKey()
        val pub = Crypto.x25519PublicKey(priv)
        val signature = Crypto.ed25519Sign(identity.ed25519Private, pub)
        return SignedPreKey(id, priv, pub, signature)
    }

    private suspend fun nextSpkId(): Int {
        val last = blobStore.getSecret(SPK_COUNTER)?.let { bytes ->
            String(bytes, Charsets.UTF_8).toInt()
        } ?: 1
        blobStore.putSecret(SPK_COUNTER, (last + 1).toString().toByteArray())
        return last
    }

    private fun loadOneTimeKeys(): List<OneTimePreKey> {
        val blob = blobStore.getSecret(OPK_BLOB) ?: return emptyList()
        val dto = json.decodeFromString<OneTimeKeysDto>(String(blob, Charsets.UTF_8))
        val b64 = Base64.getDecoder()
        return dto.keys.map { OneTimePreKey(it.id, b64.decode(it.priv), b64.decode(it.pub)) }
    }

    private fun saveOneTimeKeys(keys: List<OneTimePreKey>) {
        val b64 = Base64.getEncoder()
        val dto = OneTimeKeysDto(keys.map { OneTimeDto(it.id, b64.encodeToString(it.privateKey), b64.encodeToString(it.publicKey)) })
        blobStore.putSecret(OPK_BLOB, json.encodeToString(dto).toByteArray())
    }

    private fun encodeIdentity(identity: IdentityKeyPair): IdentityDto {
        val b64 = Base64.getEncoder()
        return IdentityDto(
            b64.encodeToString(identity.x25519Private),
            b64.encodeToString(identity.x25519Public),
            b64.encodeToString(identity.ed25519Private),
            b64.encodeToString(identity.ed25519Public),
        )
    }

    private fun decodeIdentity(blob: ByteArray): IdentityKeyPair {
        val dto = json.decodeFromString<IdentityDto>(String(blob, Charsets.UTF_8))
        val b64 = Base64.getDecoder()
        return IdentityKeyPair(b64.decode(dto.xPriv), b64.decode(dto.xPub), b64.decode(dto.ePriv), b64.decode(dto.ePub))
    }

    private fun encodeSpk(spk: SignedPreKey): SignedPreKeyDto {
        val b64 = Base64.getEncoder()
        return SignedPreKeyDto(spk.id, b64.encodeToString(spk.privateKey), b64.encodeToString(spk.publicKey), b64.encodeToString(spk.signature))
    }

    private fun decodeSpk(blob: ByteArray): SignedPreKey {
        val dto = json.decodeFromString<SignedPreKeyDto>(String(blob, Charsets.UTF_8))
        val b64 = Base64.getDecoder()
        return SignedPreKey(dto.id, b64.decode(dto.priv), b64.decode(dto.pub), b64.decode(dto.sig))
    }

    private companion object {
        const val IDENTITY_BLOB = "identity_keys"
        const val SPK_BLOB = "signed_prekey"
        const val SPK_COUNTER = "signed_prekey_counter"
        const val OPK_BLOB = "one_time_prekeys"
    }
}
