package com.joe6355.astrophoto

import android.os.Build
import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import android.os.ParcelFileDescriptor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import com.joe6355.astrophoto.ui.AstroTestTags
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ads.AdvertisingConsent
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

    @Test fun decliningIsAnAccessibleExplicitChoice() {
        var chosen: AdvertisingConsent? = null
        compose.setContent {
            AstroPhotoTheme(redNight = true) {
                AdvertisingConsentDialog(AdvertisingConsent.NOT_CHOSEN, false,
                    onChoose = { chosen = it }, onOpenPolicy = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Разрешить").assertIsDisplayed()
        compose.onNodeWithText("Без рекламы").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AdvertisingConsent.DECLINED, chosen) }
    }

    @Test fun advertisingChoiceSurvivesCameraResetAndPolicyRevisionInvalidatesIt() {
        val context = compose.activity
        val preferences = context.getSharedPreferences("astrophoto_privacy", android.content.Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        val store = CameraSettingsStore(context)
        assertEquals(AdvertisingConsent.NOT_CHOSEN, store.advertisingConsent())
        check(store.saveAdvertisingConsent(AdvertisingConsent.ALLOWED))
        store.reset()
        assertEquals(AdvertisingConsent.ALLOWED, CameraSettingsStore(context).advertisingConsent())
        check(store.saveAdvertisingConsent(AdvertisingConsent.DECLINED))
        assertEquals(AdvertisingConsent.DECLINED, CameraSettingsStore(context).advertisingConsent())
        check(preferences.edit().putString("policy_revision", "old-policy").commit())
        assertEquals(AdvertisingConsent.NOT_CHOSEN, CameraSettingsStore(context).advertisingConsent())
        check(preferences.edit().clear().commit())
    }

    @Test fun mainActivityCanReadDeclineAllowAndRevokeAdvertising() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privacyMainTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CameraSettingsStore(context)
        store.setOnboardingSeen(true)
        check(store.saveAdvertisingConsent(AdvertisingConsent.NOT_CHOSEN))
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("advertising-consent-dialog"))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText("Читать политику").assertIsDisplayed()
            compose.onNodeWithText("Разрешить").assertIsDisplayed()
            compose.onNodeWithText("Без рекламы").assertIsDisplayed()
            saveScreen("main-choice-200")
            val before = ownLogs()
            assertFalse(before.contains("native_loading"))
            assertFalse(before.contains("app_open_loading"))
            compose.onNodeWithText("Читать политику").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Политика конфиденциальности AstroPhoto", substring = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("privacy-policy-screen").performScrollToNode(
                hasText("Разработчик: Serega_6355.", substring = true))
            compose.onNodeWithText("dudin1918@mail.ru", substring = true).assertIsDisplayed()
            saveScreen("main-policy-200-offline")
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithText("Без рекламы").performClick()
            compose.onNodeWithTag("advertising-consent-dialog").assertDoesNotExist()
            assertEquals(AdvertisingConsent.DECLINED, store.advertisingConsent())
            scenario.recreate()
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag(AstroTestTags.HomeScreen))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("advertising-consent-dialog").assertDoesNotExist()
            assertFalse(ownLogs().contains("native_loading"))
            saveScreen("main-declined-recreated-200")
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            compose.waitUntil(15_000) {
                connectivity.activeNetwork?.let { network ->
                    connectivity.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            }

            fun openChoice() {
                compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToIndex(0)
                    .performScrollToNode(hasText("Настройки"))
                compose.onNodeWithText("Настройки").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Реклама и данные"))
                compose.onNodeWithText("Реклама и данные").performClick()
            }
            openChoice()
            compose.onNodeWithText("Разрешить").performClick()
            compose.onNodeWithText("Назад").performClick()
            compose.waitUntil(35_000) { ownLogs().contains("native_loaded") }
            compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToNode(
                androidx.compose.ui.test.hasTestTag("home-native-ad"))
            compose.onNodeWithTag("home-native-ad").assertIsDisplayed()
            saveScreen("main-native-granted-200")
            assertFalse(ownLogs().contains("app_open_shown"))
            openChoice()
            compose.onNodeWithText("Без рекламы").performClick()
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithTag("home-native-ad").assertDoesNotExist()
            assertEquals(AdvertisingConsent.DECLINED, store.advertisingConsent())
            val finalLog = ownLogs()
            assertEquals(1, Regex("native_loading").findAll(finalLog).count())
            saveScreen("main-revoked-200")
            File(context.getExternalFilesDir(null), "privacy-proof/main-flow.log").writeText(finalLog)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun ownLogs(): String = shell("logcat -d --pid=${Process.myPid()}")

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
