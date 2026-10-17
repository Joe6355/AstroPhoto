package com.joe6355.astrophoto

import android.app.KeyguardManager
import android.app.Activity
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Opt-in shell launch for devices which deny background Activity launches during instrumentation. */
class AstroUiForegroundRule(
    private val activityClass: Class<out Activity> = AstroUiTestActivity::class.java
) : ExternalResource() {
    override fun before() {
        if (InstrumentationRegistry.getArguments().getString("shellUiLaunch") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Unlock the device before running UI tests"
        }
        val component = ComponentName(context, activityClass).flattenToString()
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
        ).bufferedReader().use { it.readText() }
        check(output.contains("Status: ok")) { "Unable to foreground the debug test host: $output" }
        instrumentation.waitForIdleSync()
    }
}
