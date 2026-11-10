package com.joe6355.astrophoto.ads

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.AstroUiTestActivity
import com.joe6355.astrophoto.AstroHomeScreen
import com.joe6355.astrophoto.BuildConfig
import com.joe6355.astrophoto.CameraSettingsStore
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real demo traffic on a disposable emulator; never runs against publisher units or phone data. */
class AdsIntegrationTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(100)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<AstroUiTestActivity>()
    private var advertising: AstroAdsController? = null

    @Before fun requireDemoEmulator() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("adsNetworkTests") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName
            .startsWith("com.joe6355.astrophoto.adsqa"))
        check(BuildConfig.YAN_NATIVE_UNIT_ID == "demo-native-content-yandex")
        check(BuildConfig.YAN_APP_OPEN_UNIT_ID == "demo-appopenad-yandex")
        // Existing-user precondition for the subsequent MainActivity smoke on this disposable QA only.
        CameraSettingsStore(compose.activity).setOnboardingSeen(true)
        check(CameraSettingsStore(compose.activity).saveAdvertisingEnabled(true))
    }

    @After fun releaseSdkResources() {
        compose.activity.runOnUiThread { advertising?.close() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun demoNativeBindsRequiredAssetsAndReusesItsView() {
        var showHome by mutableStateOf(true)
        var card: NativeAdCardView? = null
        compose.activity.runOnUiThread {
            advertising = AstroAdsController(compose.activity, startupEligible = false).also {
                it.startStartup()
                it.finishStartup()
                it.setNativeAllowed(true)
            }
        }
        compose.setContent {
            card = rememberNativeAdCard(advertising?.nativeAd)
            AstroPhotoTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                if (showHome) AstroHomeScreen({}, {}, {}, nativeAdContent = card?.let { view ->
                    { HomeNativeAdCard(view) }
                }) else Text("Другой тестовый экран")
                }
            }
        }
        compose.waitUntil(40_000) { advertising?.nativeAd != null && card != null }
        compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToNode(hasTestTag("home-native-ad"))
        compose.onNodeWithTag("home-native-ad").assertIsDisplayed()
        compose.runOnIdle {
            val view = requireNotNull(card)
            assertTrue(view.bound)
            val assets = requireNotNull(advertising?.nativeAd).adAssets
            assertFalse("Demo title must be present", assets.title.isNullOrBlank())
            assertFalse("Demo domain must be present", assets.domain.isNullOrBlank())
            assertFalse("Demo disclosure must be present", assets.sponsored.isNullOrBlank())
            val texts = descendants(view).filterIsInstance<TextView>()
            listOf(assets.title, assets.domain, assets.sponsored, assets.age, assets.price, assets.warning?.value)
                .filterNotNull().filter { it.isNotBlank() }.forEach { expected ->
                    val asset = texts.single { it.text.toString() == expected }
                    assertEquals(View.VISIBLE, asset.visibility)
                    val rect = Rect()
                    assertTrue("Mandatory asset is outside the visible card: $expected", asset.getGlobalVisibleRect(rect))
                    assertEquals(asset.height, rect.height())
                    assertEquals(asset.width, rect.width())
                    assertTrue(asset.layout.height <= asset.height - asset.paddingTop - asset.paddingBottom)
                }
        }
        screenshot("native-${compose.activity.resources.configuration.fontScale}")
        val original = card
        compose.runOnIdle { showHome = false; advertising?.setNativeAllowed(false) }
        compose.runOnIdle { showHome = true; advertising?.setNativeAllowed(true) }
        compose.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToNode(hasTestTag("home-native-ad"))
        compose.onNodeWithTag("home-native-ad").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, card) }
    }

    @Test fun demoAppOpenCanShowCloseAndCannotReplayAfterResume() {
        compose.setContent { AstroPhotoTheme { Text("Проверка рекламы при запуске") } }
        compose.activity.runOnUiThread {
            // Test-only window permits a real network load. Production keeps its existing 700 ms.
            advertising = AstroAdsController(compose.activity, startupEligible = true,
                startupPolicy = StartupAdPolicy(windowMillis = 40_000)).also { it.startStartup() }
        }
        compose.waitUntil(40_000) { advertising?.appOpenShowing == true }
        try {
            compose.waitUntil(20_000) { closeControl() != null }
        } catch (failure: Throwable) {
            screenshot("app-open-close-timeout")
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val tree = StringBuilder()
            fun describe(node: AccessibilityNodeInfo) {
                tree.appendLine("${node.packageName}: text=${node.text}; description=${node.contentDescription}; " +
                    "id=${node.viewIdResourceName}; clickable=${node.isClickable}; visible=${node.isVisibleToUser}")
                repeat(node.childCount) { index -> node.getChild(index)?.let(::describe) }
            }
            root?.let(::describe)
            File(compose.activity.getExternalFilesDir(null), "ads-proof/app-open-close-timeout.txt")
                .writeText(tree.toString())
            throw failure
        }
        screenshot("app-open")
        assertTrue(requireNotNull(closeControl()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(10_000) { advertising?.appOpenShowing == false }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertFalse(requireNotNull(advertising).appOpenShowing) }
        screenshot("app-open-dismissed")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val frames = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue(frames.await(2, TimeUnit.SECONDS))
        val directory = File(compose.activity.getExternalFilesDir(null), "ads-proof").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun closeControl(): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        fun findLabel(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == "Вернуться в приложение") return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                findLabel(child)?.let { return it }
            }
            return null
        }
        var node = findLabel(root) ?: return null
        repeat(5) {
            if (node.isClickable) return node
            node = node.parent ?: return null
        }
        return null
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
