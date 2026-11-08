package com.joe6355.astrophoto.ads

import org.junit.Assert.*
import org.junit.Test

class StartupAdPolicyTest {
    @Test fun onlyACurrentExplicitChoiceCanAllowAdvertising() {
        val revision = AdvertisingConsent.POLICY_REVISION
        assertEquals(AdvertisingConsent.NOT_CHOSEN, AdvertisingConsent.restore(null, null))
        assertEquals(AdvertisingConsent.NOT_CHOSEN, AdvertisingConsent.restore("ALLOWED", "old-policy"))
        assertEquals(AdvertisingConsent.NOT_CHOSEN, AdvertisingConsent.restore("unknown", revision))
        assertEquals(AdvertisingConsent.ALLOWED, AdvertisingConsent.restore("ALLOWED", revision))
        assertEquals(AdvertisingConsent.DECLINED, AdvertisingConsent.restore("DECLINED", revision))
    }

    @Test fun readyAdCanBeShownOnceInsideSplash() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = true)
        assertTrue(policy.request(1_050, allowed = true))
        assertFalse(policy.request(1_060, allowed = true))
        assertTrue(policy.present(1_699, allowed = true))
        assertFalse(policy.present(1_699, allowed = true))
    }

    @Test fun timeoutNeverAllowsAnAdToInterruptHome() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = true)
        assertTrue(policy.request(1_050, allowed = true))
        assertFalse(policy.present(1_700, allowed = true))
        assertFalse(policy.present(1_701, allowed = true))
    }

    @Test fun lateInitializationDoesNotEvenRequestAnAppOpenAd() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = true)
        assertFalse(policy.request(1_700, allowed = true))
    }

    @Test fun backgroundingOrNavigationPermanentlyClosesThisOpportunity() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = true)
        assertTrue(policy.request(1_050, allowed = true))
        policy.finish()
        policy.start(1_100, eligible = true)
        assertFalse(policy.present(1_150, allowed = true))
    }

    @Test fun captureOrProcessingStartingWhileAdLoadsPreventsPresentation() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = true)
        assertTrue(policy.request(1_010, allowed = true))
        assertFalse(policy.present(1_100, allowed = false))
        assertFalse(policy.present(1_101, allowed = true))
    }

    @Test fun onboardingAndRecoveryDoNotOpenAnOpportunityLater() {
        val policy = StartupAdPolicy()
        policy.start(1_000, eligible = false)
        policy.start(1_100, eligible = true)
        assertFalse(policy.request(1_101, allowed = true))
    }

    @Test fun missingRequestOrUnstartedWindowCannotShowAnything() {
        assertFalse(StartupAdPolicy().present(0, allowed = true))
        val policy = StartupAdPolicy()
        policy.start(100, eligible = true)
        assertFalse(policy.present(101, allowed = true))
    }

    @Test fun hotActivityAndRecreationNeverCountAsASecondColdStart() {
        val process = AdProcessSession()
        assertTrue(process.claimColdStart(restoredActivity = false))
        assertFalse(process.claimColdStart(restoredActivity = false))
        assertFalse(process.claimColdStart(restoredActivity = true))
    }

    @Test fun firstRestoredActivityDoesNotIntroduceAnAdOnRecovery() {
        val process = AdProcessSession()
        assertFalse(process.claimColdStart(restoredActivity = true))
        assertFalse(process.claimColdStart(restoredActivity = false))
    }
}
