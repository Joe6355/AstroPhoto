package com.joe6355.astrophoto.ads

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.joe6355.astrophoto.BuildConfig
import com.joe6355.astrophoto.CameraSettingsStore
import com.joe6355.astrophoto.SeriesCaptureCoordinator
import com.joe6355.astrophoto.SessionProcessingCoordinator
import com.yandex.mobile.ads.appopenad.AppOpenAd
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.nativeads.NativeAd
import com.yandex.mobile.ads.nativeads.NativeAdEventListener
import com.yandex.mobile.ads.nativeads.NativeAdLoadListener
import com.yandex.mobile.ads.nativeads.NativeAdLoader
import com.yandex.mobile.ads.nativeads.NativeAdOptions
import java.lang.ref.WeakReference

/** Activity-owned SDK resources; neither the process gate nor SDK init keeps an Activity alive. */
internal class AstroAdsController(
    private val activity: ComponentActivity,
    private val startupEligible: Boolean,
    private val startupPolicy: StartupAdPolicy = StartupAdPolicy()
) : DefaultLifecycleObserver {
    var nativeAd by mutableStateOf<NativeAd?>(null)
        private set
    var appOpenShowing by mutableStateOf(false)
        private set
    private var closed = false
    private var advertisingEnabled = CameraSettingsStore(activity).advertisingEnabled()
    private var initializationRequested = false
    private var initialized = false
    private var nativeAllowed = false
    private var nativeRequested = false
    private var nativeLoader: NativeAdLoader? = null
    private var appOpenLoader: AppOpenAdLoader? = null
    private var appOpenAd: AppOpenAd? = null

    init { activity.lifecycle.addObserver(this) }

    fun startStartup() {
        if (closed) return
        startupPolicy.start(SystemClock.elapsedRealtime(), startupEligible && advertisingEnabled && !busy())
        if (!startupPolicy.finished) initialize()
    }

    fun finishStartup() {
        startupPolicy.finish()
        appOpenLoader?.cancelLoading()
        appOpenLoader = null
    }

    fun setNativeAllowed(allowed: Boolean) {
        nativeAllowed = allowed
        if (!allowed) {
            val loader = nativeLoader
            nativeLoader = null
            if (nativeAd == null) nativeRequested = false
            loader?.cancelLoading()
        } else if (!closed) {
            initialize()
            loadNativeIfAllowed()
        }
    }

    fun updateAdvertisingEnabled(enabled: Boolean) {
        if (closed || advertisingEnabled == enabled) return
        advertisingEnabled = enabled
        if (!enabled) {
            finishStartup()
            setNativeAllowed(false)
            nativeRequested = false
            nativeAd?.setNativeAdEventListener(null)
            nativeAd = null
        }
    }

    private fun initialize() {
        if (closed || !advertisingEnabled || initializationRequested) return
        initializationRequested = true
        // Showing ads is a display preference. Configure the age-restricted SDK mode before it starts.
        YandexAds.setUserConsent(false)
        YandexAds.setAgeRestricted(true)
        YandexAds.setLocationTracking(false)
        YandexAds.setAppAdAnalyticsReporting(false)
        YandexAds.enableLogging(BuildConfig.DEBUG)
        val owner = WeakReference(this)
        try {
            YandexAds.initialize(activity.applicationContext) {
                owner.get()?.onInitialized()
            }
        } catch (_: Exception) {
            finishStartup()
            Log.w(TAG, "sdk_initialization_failed")
        }
    }

    private fun onInitialized() {
        if (closed) return
        initialized = true
        if (!advertisingEnabled) {
            finishStartup()
            return
        }
        if (startupPolicy.request(SystemClock.elapsedRealtime(), canPresent())) {
            val loader = AppOpenAdLoader(activity.application)
            appOpenLoader = loader
            Log.d(TAG, "app_open_loading")
            try {
                loader.loadAd(AdRequest.Builder(BuildConfig.YAN_APP_OPEN_UNIT_ID).build(),
                object : AppOpenAdLoadListener {
                    override fun onAdLoaded(appOpenAd: AppOpenAd) {
                        val ad = appOpenAd
                        if (closed || !startupPolicy.present(SystemClock.elapsedRealtime(), canPresent())) {
                            ad.setAdEventListener(null)
                            Log.d(TAG, "app_open_skipped_late_or_ineligible")
                            return
                        }
                        this@AstroAdsController.appOpenAd = ad
                        appOpenShowing = true
                        ad.setAdEventListener(object : AppOpenAdEventListener {
                            override fun onAdShown() { Log.d(TAG, "app_open_shown") }
                            override fun onAdFailedToShow(adError: AdError) { finishPresentation() }
                            override fun onAdDismissed() { finishPresentation() }
                            override fun onAdClicked() = Unit
                            override fun onAdImpression(impressionData: ImpressionData?) = Unit
                        })
                        try {
                            ad.show(activity)
                        } catch (_: Exception) {
                            finishPresentation()
                        }
                    }

                    override fun onAdFailedToLoad(error: AdRequestError) {
                        finishStartup()
                        Log.d(TAG, "app_open_load_failed:${error.code}")
                    }
                })
            } catch (_: Exception) {
                finishStartup()
                Log.w(TAG, "app_open_request_failed")
            }
        }
        loadNativeIfAllowed()
    }

    private fun canPresent() = !closed && advertisingEnabled && !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !busy()

    private fun finishPresentation() {
        appOpenAd?.setAdEventListener(null)
        appOpenAd = null
        appOpenShowing = false
        finishStartup()
        Log.d(TAG, "app_open_finished")
    }

    private fun loadNativeIfAllowed() {
        if (closed || !initialized || !nativeAllowed || nativeRequested ||
            !startupPolicy.finished || appOpenShowing || !canPresent()) return
        nativeRequested = true
        val loader = NativeAdLoader(activity)
        nativeLoader = loader
        Log.d(TAG, "native_loading")
        try {
            loader.loadAd(
            AdRequest.Builder(BuildConfig.YAN_NATIVE_UNIT_ID).build(),
            NativeAdOptions.Builder().setShouldLoadImagesAutomatically(true).build(),
            object : NativeAdLoadListener {
                override fun onAdLoaded(nativeAd: NativeAd) {
                    val ad = nativeAd
                    if (nativeLoader !== loader) {
                        ad.setNativeAdEventListener(null)
                        return
                    }
                    nativeLoader = null
                    ad.setNativeAdEventListener(object : NativeAdEventListener {
                        override fun onAdClicked() = Unit
                        override fun onImpression(impressionData: ImpressionData?) { Log.d(TAG, "native_impression") }
                    })
                    this@AstroAdsController.nativeAd = ad
                    Log.d(TAG, "native_loaded")
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    if (nativeLoader !== loader) return
                    nativeLoader = null
                    Log.d(TAG, "native_load_failed:${error.code}")
                    // Retry only when the home screen becomes eligible again.
                }
            })
        } catch (_: Exception) {
            nativeLoader = null
            Log.w(TAG, "native_request_failed")
        }
    }

    override fun onResume(owner: LifecycleOwner) { loadNativeIfAllowed() }

    override fun onPause(owner: LifecycleOwner) {
        if (!appOpenShowing) finishStartup()
    }

    override fun onDestroy(owner: LifecycleOwner) { close() }

    fun close() {
        if (closed) return
        closed = true
        finishStartup()
        setNativeAllowed(false)
        nativeAd?.setNativeAdEventListener(null)
        nativeAd = null
        appOpenAd?.setAdEventListener(null)
        appOpenAd = null
        appOpenShowing = false
        activity.lifecycle.removeObserver(this)
    }

    private fun busy() = SeriesCaptureCoordinator.state.value?.running == true ||
        SessionProcessingCoordinator.states.value.values.any { it.running }

    companion object { private const val TAG = "AstroPhotoAds" }
}
