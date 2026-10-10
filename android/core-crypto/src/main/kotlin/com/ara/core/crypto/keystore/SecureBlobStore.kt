package com.ara.core.crypto.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString

/**
 * Stores small secrets (identity keys, DB passphrase) encrypted at rest with an
 * AES-256-GCM key held in the Android Keystore — hardware-backed where the
 * device supports it. Secret material never appears in plaintext prefs.
 */
@Singleton
class SecureBlobStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun putSecret(name: String, secret: ByteArray) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret)
        prefs.edit()
            .putString("$name.iv", java.util.Base64.getEncoder().encodeToString(iv))
            .putString("$name.blob", java.util.Base64.getEncoder().encodeToString(encrypted))
            .apply()
    }

    fun getSecret(name: String): ByteArray? {
        val iv = prefs.getString("$name.iv", null) ?: return null
        val blob = prefs.getString("$name.blob", null) ?: return null
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, java.util.Base64.getDecoder().decode(iv)),
        )
        return cipher.doFinal(java.util.Base64.getDecoder().decode(blob))
    }

    fun removeSecret(name: String) {
        prefs.edit().remove("$name.iv").remove("$name.blob").apply()
    }

    fun contains(name: String): Boolean = prefs.contains("$name.blob")

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "ara_secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "ara_master_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
