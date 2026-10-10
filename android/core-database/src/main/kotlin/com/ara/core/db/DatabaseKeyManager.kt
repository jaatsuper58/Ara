package com.ara.core.db

import com.ara.core.crypto.keystore.SecureBlobStore
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SQLCipher passphrase: 32 random bytes generated on first run, wrapped by
 * the Android Keystore (hardware-backed where available) via [SecureBlobStore].
 * Deriving the DB key from both Keystore and (optionally, in a future rev) the
 * user's passcode follows the spec's at-rest design.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    private val blobStore: SecureBlobStore,
) {
    fun getOrCreatePassphrase(): ByteArray {
        blobStore.getSecret(DB_KEY_NAME)?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        blobStore.putSecret(DB_KEY_NAME, key)
        return key
    }

    fun wipe() = blobStore.removeSecret(DB_KEY_NAME)

    private companion object {
        const val DB_KEY_NAME = "database_passphrase"
    }
}
