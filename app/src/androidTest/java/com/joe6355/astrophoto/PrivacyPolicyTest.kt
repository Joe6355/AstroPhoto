package com.joe6355.astrophoto

import android.os.Build
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import com.joe6355.astrophoto.ui.AstroTestTags
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PrivacyPolicyTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(120)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()

    @Before fun onlyOwnDisposableQa() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
            "com.joe6355.astrophoto.adsqa20261107privacy")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(BuildConfig.DEBUG && BuildConfig.YAN_NATIVE_UNIT_ID == "demo-native-content-yandex")
    }

    @Test fun bundledPolicyShowsOwnerAndContactWithoutNetwork() {
        compose.setContent { AstroPhotoTheme { PrivacyPolicyScreen(onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Политика конфиденциальности AstroPhoto", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("privacy-policy-screen").performScrollToNode(
            androidx.compose.ui.test.hasText("Разработчик: Serega_6355.", substring = true))
        compose.onNodeWithText("Разработчик: Serega_6355.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("dudin1918@mail.ru", substring = true).assertIsDisplayed()
    }

    @Test fun advertisingPreferenceDefaultsOnAndPreservesLegacyRefusal() {
        val context = compose.activity
        val preferences = context.getSharedPreferences("astrophoto_privacy", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        val store = CameraSettingsStore(context)
        try {
            assertTrue(store.advertisingEnabled())
            check(preferences.edit().putString("advertising_consent", "NOT_CHOSEN").commit())
            assertTrue(store.advertisingEnabled())
            check(preferences.edit().putString("advertising_consent", "DECLINED")
                .putString("policy_revision", "2026-11-08").commit())
            assertFalse(store.advertisingEnabled())
            check(store.saveAdvertisingEnabled(true))
            assertTrue(CameraSettingsStore(context).advertisingEnabled())
            check(store.saveAdvertisingEnabled(false))
            store.reset()
            assertFalse(CameraSettingsStore(context).advertisingEnabled())
        } finally {
            check(preferences.edit().clear().commit())
        }
    }

    @Test fun mainActivityLoadsDefaultAdsAndLetsUsersTurnThemOffAndOn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privacyMainTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CameraSettingsStore(context)
        store.setOnboardingSeen(true)
        check(context.getSharedPreferences("astrophoto_privacy", Context.MODE_PRIVATE).edit().clear().commit())
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            val advertisingRow = hasTestTag("settings-advertising")
            val adSwitch = compose.onNode(isToggleable() and hasAnyAncestor(advertisingRow))
            fun openSettings() {
                compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToIndex(0)
                    .performScrollToNode(hasText("Настройки"))
                compose.onNodeWithText("Настройки").performClick()
            }
            fun scrollToAdvertising() =
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(advertisingRow)
            fun checkNativeAd() {
                compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToNode(hasTestTag("home-native-ad"))
                compose.onNodeWithTag("home-native-ad").assertIsDisplayed()
            }

            compose.waitUntil(30_000) { ownLogs().contains("native_loaded") }
            checkNativeAd()
            compose.onNodeWithText("Реклама и данные").assertDoesNotExist()
            assertTrue(store.advertisingEnabled())
            saveScreen("main-default-native-200")
            openSettings()
            scrollToAdvertising()
            adSwitch.assertIsDisplayed().assertIsOn().performClick().assertIsOff()
            assertFalse(store.advertisingEnabled())
            saveScreen("settings-advertising-off-200")

            compose.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Политика конфиденциальности"))
            compose.onNodeWithText("Политика конфиденциальности").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Политика конфиденциальности AstroPhoto", substring = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("privacy-policy-screen").performScrollToNode(
                hasText("Разработчик: Serega_6355.", substring = true))
            compose.onNodeWithText("dudin1918@mail.ru", substring = true).assertIsDisplayed()
            saveScreen("main-policy-200")
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithTag("home-native-ad").assertDoesNotExist()
            assertEquals(1, Regex("native_loading").findAll(ownLogs()).count())

            // Re-enabling in the same Activity must allow a fresh native request.
            openSettings()
            scrollToAdvertising()
            adSwitch.assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(30_000) { Regex("native_loaded").findAll(ownLogs()).count() == 2 }
            checkNativeAd()

            openSettings()
            scrollToAdvertising()
            adSwitch.performClick().assertIsOff()
            compose.onNodeWithText("Назад").performClick()
            scenario.recreate()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag(AstroTestTags.HomeScreen))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("home-native-ad").assertDoesNotExist()
            assertFalse(store.advertisingEnabled())
            val finalLog = ownLogs()
            assertEquals(2, Regex("native_loading").findAll(finalLog).count())
            assertFalse(finalLog.contains("app_open_shown"))
            saveScreen("main-advertising-off-200")
            File(context.getExternalFilesDir(null), "privacy-proof/main-flow.log").writeText(finalLog)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun ownLogs(): String = shell("logcat -d --pid=${Process.myPid()} AstroPhotoAds:D '*:S'")

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        Thread.sleep(200) // Let the stable tested layout reach the emulator surface.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "privacy-proof").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
