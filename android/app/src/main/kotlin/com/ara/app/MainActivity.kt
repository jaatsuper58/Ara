package com.ara.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.app.navigation.AraApp
import com.ara.app.security.AppLockGate
import com.ara.core.common.ThemeMode
import com.ara.core.common.UserPrefsRepository
import com.ara.core.ui.theme.AraTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose host. Extends FragmentActivity for BiometricPrompt.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var prefsRepository: UserPrefsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(AppLockGate(this, prefsRepository))

        setContent {
            val prefs = prefsRepository.prefs.collectAsStateWithLifecycle(
                initialValue = com.ara.core.common.UserPrefs(),
            )
            AraTheme(darkTheme = prefs.value.theme == ThemeMode.DARK) {
                AraApp()
            }
        }
    }
}
