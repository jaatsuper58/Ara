package com.ara.feature.settings.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.ara.core.crypto.primitives.Crypto
import com.ara.feature.settings.data.UserPrefsRepositoryImpl
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level lock independent of the device lock (spec §2): biometric unlock
 * with a numeric-passcode fallback. The passcode is stored as a PBKDF2 hash —
 * never in plaintext.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val prefsRepository: UserPrefsRepositoryImpl,
) {

    fun canUseBiometrics(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Unlock Ara",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(code: Int, message: CharSequence) = onError(message.toString())
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    /** PBKDF2-SHA256 (150k iterations) hash for the numeric passcode. */
    fun hashPasscode(passcode: String, salt: ByteArray = Crypto.randomBytes(16)): String {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, 150_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return salt.joinToString("") { "%02x".format(it) } + ":" +
            hash.joinToString("") { "%02x".format(it) }
    }

    fun verifyPasscode(passcode: String, stored: String): Boolean {
        val saltHex = stored.substringBefore(":")
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return hashPasscode(passcode, salt) == stored
    }
}
