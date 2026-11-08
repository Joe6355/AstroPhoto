package com.joe6355.astrophoto.ads

enum class AdvertisingConsent {
    NOT_CHOSEN, ALLOWED, DECLINED;

    companion object {
        const val POLICY_REVISION = "2026-11-08"

        fun restore(choice: String?, revision: String?): AdvertisingConsent =
            if (revision == POLICY_REVISION) entries.firstOrNull { it.name == choice } ?: NOT_CHOSEN
            else NOT_CHOSEN
    }
}

/** A cold launch gets one opportunity inside the existing splash, never after content opens. */
internal class StartupAdPolicy(private val windowMillis: Long = SPLASH_MILLIS) {
    private var startedAt: Long? = null
    private var requested = false
    var finished = false
        private set

    fun start(now: Long, eligible: Boolean) {
        if (startedAt != null || finished) return
        startedAt = now
        if (!eligible) finished = true
    }

    fun request(now: Long, allowed: Boolean): Boolean {
        if (requested || !withinWindow(now) || !allowed) return false
        requested = true
        return true
    }

    fun present(now: Long, allowed: Boolean): Boolean {
        val accepted = requested && withinWindow(now) && allowed
        finished = true
        return accepted
    }

    fun finish() { finished = true }

    private fun withinWindow(now: Long): Boolean {
        val start = startedAt ?: return false
        return !finished && now >= start && now - start < windowMillis
    }

    companion object { const val SPLASH_MILLIS = 700L }
}

/** Activity recreation and returning from another app cannot create a second cold launch. */
internal class AdProcessSession {
    private var claimed = false

    fun claimColdStart(restoredActivity: Boolean): Boolean {
        val cold = !claimed && !restoredActivity
        claimed = true
        return cold
    }
}

internal object AstroAdProcess { val session = AdProcessSession() }
