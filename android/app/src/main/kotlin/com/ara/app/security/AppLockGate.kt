package com.ara.app.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ara.core.common.UserPrefsRepository
import com.ara.feature.settings.security.AppLockManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * App-level lock gate (spec §2): whenever the app is backgrounded while the
 * lock is enabled, an opaque overlay covers it and biometric/passcode unlock
 * is required on return. Independent of the device lock.
 */
class AppLockGate(
    private val activity: androidx.fragment.app.FragmentActivity,
    private val prefsRepository: UserPrefsRepository,
) : DefaultLifecycleObserver {

    private var locked = false

    override fun onStart(owner: LifecycleOwner) {
        if (!locked) return
        val appLockManager = EntryPointAccessors.fromApplication(
            activity.applicationContext,
            LockEntryPoint::class.java,
        ).appLockManager()

        val prefs = runBlocking { prefsRepository.prefs.first() }
        if (!prefs.appLockEnabled) return

        appLockManager.showBiometricPrompt(
            activity = activity,
            onSuccess = { locked = false },
            onError = {
                // Stay locked; the overlay remains until the user unlocks.
            },
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        val prefs = runBlocking { prefsRepository.prefs.first() }
        locked = prefs.appLockEnabled
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LockEntryPoint {
    fun appLockManager(): com.ara.feature.settings.security.AppLockManager
}
