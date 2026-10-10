package com.ara.core.crypto.primitives

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level primitives for the Ara protocol, implemented on BouncyCastle's
 * constant-time X25519/Ed25519 and the JVM's AES-GCM.
 *
 * All raw key material is exactly 32 bytes (RFC 7748 / RFC 8032 sized).
 */
object Crypto {

    val random = SecureRandom()
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_BITS = 128

    // ---------- X25519 ----------

    fun generateX25519PrivateKey(): ByteArray =
        ByteArray(KEY_SIZE).also { X25519.generatePrivateKey(random, it) }

    fun x25519PublicKey(privateKey: ByteArray): ByteArray =
        ByteArray(KEY_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val out = ByteArray(KEY_SIZE)
        require(X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0))
        return out
    }

    // ---------- Ed25519 ----------

    fun generateEd25519PrivateKey(): ByteArray =
        ByteArray(Ed25519.SECRET_KEY_SIZE).also { Ed25519.generatePrivateKey(random, it) }

    fun ed25519PublicKey(privateKey: ByteArray): ByteArray =
        ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(privateKey, 0, it, 0) }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val pub = ed25519PublicKey(privateKey)
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(privateKey, 0, pub, 0, message, 0, message.size, sig, 0)
        return sig
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)

    // ---------- AES-256-GCM ----------

    fun generateNonce(): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // ---------- KDF / MAC / hash ----------

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}

/** Empty all-zero input to the X3DH KDF (Signal spec: F = 0xFF repeated 32 times). */
internal val X3DH_F: ByteArray = ByteArray(32) { 0xFF.toByte() }
