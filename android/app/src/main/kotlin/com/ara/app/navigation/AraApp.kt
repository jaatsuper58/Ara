package com.ara.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ara.app.onboarding.OnboardingScreen
import com.ara.feature.calls.ui.CallsScreen
import com.ara.feature.calls.ui.InCallScreen
import com.ara.feature.chat.ui.ChatListScreen
import com.ara.feature.chat.ui.ChatThreadScreen
import com.ara.feature.chat.ui.GroupInfoScreen
import com.ara.feature.contacts.ui.ContactsScreen
import com.ara.feature.settings.ui.SettingsScreen

sealed class Destination(val route: String) {
    data object Onboarding : Destination("onboarding")
    data object Chats : Destination("chats")
    data object Calls : Destination("calls")
    data object Contacts : Destination("contacts")
    data object Settings : Destination("settings")
    data object Thread : Destination("thread/{chatId}") {
        fun of(chatId: String) = "thread/$chatId"
    }
    data object GroupInfo : Destination("group/{chatId}") {
        fun of(chatId: String) = "group/$chatId"
    }
    data object InCall : Destination("incall")
    data object SafetyNumber : Destination("safety")
}

private data class Tab(val destination: Destination, val icon: ImageVector, val label: String)

private val tabs = listOf(
    Tab(Destination.Chats, Icons.AutoMirrored.Filled.Chat, "Chats"),
    Tab(Destination.Calls, Icons.Default.Call, "Calls"),
    Tab(Destination.Contacts, Icons.Default.People, "Contacts"),
    Tab(Destination.Settings, Icons.Default.Settings, "Settings"),
)

@Composable
fun AraApp() {
    val navController: NavHostController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = currentRoute in tabs.map { it.destination.route }

    // Signed-out state routes to onboarding; the account flow decides.
    val accountViewModel: HomeViewModel = hiltViewModel()
    val signedIn by accountViewModel.signedIn.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.destination.route,
                            onClick = {
                                navController.navigate(tab.destination.route) {
                                    popUpTo(Destination.Chats.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.destination == Destination.Calls) {
                                    BadgedBox(badge = {
                                        val missed by accountViewModel.missedCalls.collectAsStateWithLifecycle()
                                        if (missed > 0) Badge { Text(missed.toString()) }
                                    }) { Icon(tab.icon, contentDescription = tab.label) }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Onboarding.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Onboarding.route) {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(Destination.Chats.route) {
                            popUpTo(Destination.Onboarding.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Destination.Chats.route) {
                ChatListScreen(onOpenChat = { chat ->
                    navController.navigate(Destination.Thread.of(chat.id))
                })
            }
            composable(Destination.Calls.route) {
                CallsScreen(onStartCall = { peerId, video ->
                    accountViewModel.placeCall(peerId, video)
                    navController.navigate(Destination.InCall.route)
                })
            }
            composable(Destination.Contacts.route) {
                ContactsScreen(
                    onOpenChat = { peerId, _ ->
                        accountViewModel.openDirectChat(peerId) { chatId ->
                            navController.navigate(Destination.Thread.of(chatId))
                        }
                    },
                    onScanQr = { /* zxing-embedded ScanContract activity handled inside ContactsScreen */ },
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenSafetyNumber = { navController.navigate(Destination.SafetyNumber.route) },
                    onSignOut = {
                        navController.navigate(Destination.Onboarding.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Destination.Thread.route,
                arguments = listOf(navArgument("chatId") { }),
            ) {
                ChatThreadScreen(
                    onBack = { navController.popBackStack() },
                    onStartCall = { chat, video ->
                        val peer = chat.peerId
                        if (peer != null) {
                            accountViewModel.placeCall(peer, video)
                            navController.navigate(Destination.InCall.route)
                        }
                    },
                    onOpenGroup = { chatId ->
                        navController.navigate(Destination.GroupInfo.of(chatId))
                    },
                )
            }
            composable(Destination.GroupInfo.route) {
                GroupInfoScreen(
                    onBack = { navController.popBackStack() },
                    onLeft = {
                        navController.popBackStack(Destination.Chats.route, inclusive = false)
                    },
                )
            }
            composable(Destination.InCall.route) {
                InCallScreen(onHangUp = { navController.popBackStack() })
            }
            composable(Destination.SafetyNumber.route) {
                com.ara.feature.settings.ui.SafetyNumberScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
