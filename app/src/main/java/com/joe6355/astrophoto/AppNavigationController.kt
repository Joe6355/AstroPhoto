package com.joe6355.astrophoto

import androidx.compose.runtime.mutableStateOf

internal enum class AppScreen {
    Diagnostics, DiagnosticsDetails, Camera, Sessions, SessionDetails,
    Settings, Help, About, SelfCheck
}

/** Navigation state has one owner; screen composables only dispatch actions. */
internal class AppNavigationController {
    val currentScreen = mutableStateOf(AppScreen.Diagnostics)
    val showExitDialog = mutableStateOf(false)
    private val backStack = mutableListOf<AppScreen>()

    fun navigateTo(screen: AppScreen) {
        if (currentScreen.value != screen) {
            backStack += currentScreen.value
            currentScreen.value = screen
        }
    }

    fun navigateBack() {
        when {
            backStack.isNotEmpty() -> currentScreen.value = backStack.removeAt(backStack.lastIndex)
            currentScreen.value != AppScreen.Diagnostics -> currentScreen.value = AppScreen.Diagnostics
            else -> showExitDialog.value = true
        }
    }

    fun navigateToSessionsAfterDelete() {
        backStack.clear()
        backStack += AppScreen.Diagnostics
        currentScreen.value = AppScreen.Sessions
    }

    /** Root tabs must not accumulate a history of repeated tab selections. */
    fun navigateTopLevel(screen: AppScreen) {
        require(screen == AppScreen.Diagnostics || screen == AppScreen.Camera || screen == AppScreen.Sessions)
        if (currentScreen.value == screen) return
        backStack.clear()
        if (screen != AppScreen.Diagnostics) backStack += AppScreen.Diagnostics
        showExitDialog.value = false
        currentScreen.value = screen
    }
}
