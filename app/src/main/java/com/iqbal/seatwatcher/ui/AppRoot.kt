package com.iqbal.seatwatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iqbal.seatwatcher.core.source.SiteUrls
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: Long?) : Screen
    data class Results(val id: Long) : Screen
    data class Web(val url: String, val login: Boolean) : Screen
    data object History : Screen
    data object Settings : Screen
    data object Onboarding : Screen
}

/** Small explicit back stack; the app has few screens and no deep links beyond notifications. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.lastOrNull() != s) stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

@Composable
fun AppRoot(pendingNav: MutableStateFlow<Screen?>) {
    val vm: AppViewModel = viewModel()
    val nav = remember { Navigator() }
    val snackbar = remember { SnackbarHostState() }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by pendingNav.collectAsState()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(pending) {
        pending?.let { nav.push(it); pendingNav.value = null }
    }

    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!s.onboardingDone && nav.current !is Screen.Web) {
        OnboardingScreen(vm, onLogin = { nav.push(Screen.Web(SiteUrls.LOGIN, login = true)) }, onFinish = { vm.setOnboardingDone(true) }, onBack = null)
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    when (val screen = nav.current) {
        Screen.Home -> HomeScreen(vm, nav, snackbar)
        is Screen.Edit -> EditWatchScreen(vm, screen.id, onDone = { nav.pop() }, snackbar)
        is Screen.Results -> ResultsScreen(vm, screen.id, nav, snackbar)
        is Screen.Web -> WebScreen(
            url = screen.url, loginMode = screen.login,
            onClose = { nav.pop() },
            onDone = { nav.pop(); vm.sessionCheck() },
        )
        Screen.History -> HistoryScreen(vm, nav, snackbar)
        Screen.Settings -> SettingsScreen(vm, nav, snackbar)
        Screen.Onboarding -> OnboardingScreen(
            vm, onLogin = { nav.push(Screen.Web(SiteUrls.LOGIN, login = true)) },
            onFinish = { vm.setOnboardingDone(true); nav.pop() }, onBack = { nav.pop() },
        )
    }
}
