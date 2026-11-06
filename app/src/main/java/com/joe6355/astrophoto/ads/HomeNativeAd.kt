package com.joe6355.astrophoto.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.common.AdBindingResult
import com.yandex.mobile.ads.nativeads.MediaView
import com.yandex.mobile.ads.nativeads.NativeAd
import com.yandex.mobile.ads.nativeads.NativeAdView
import com.yandex.mobile.ads.nativeads.NativeAdViewBinder
import kotlin.math.roundToInt

/** Remember at the app root, not in a lazy item: scrolling and navigation reuse the same bound View. */
@Composable
internal fun rememberNativeAdCard(ad: NativeAd?): NativeAdCardView? {
    val context = LocalContext.current
    val view = remember(ad, context) {
        ad?.let { NativeAdCardView(context, it) }?.takeIf { it.bound }
    }
    DisposableEffect(view) { onDispose { view?.release() } }
    return view
}

@Composable
internal fun HomeNativeAdCard(view: NativeAdCardView) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints {
        // A video needs 300x160 dp inside the card, in addition to its padding.
        if (!view.hasVideo || maxWidth >= 324.dp) {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxWidth().testTag("home-native-ad"),
                update = {
                    it.applyPalette(colors.surface.toArgb(), colors.onSurface.toArgb(),
                        colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(), colors.outlineVariant.toArgb())
                }
            )
        }
    }
}

/** Compact image layout; mandatory assets are SDK-bound and never covered by custom click handlers. */
internal class NativeAdCardView(context: Context, ad: NativeAd) : FrameLayout(context) {
    private val adView = NativeAdView(context)
    private val title = text(14f, bold = true)
    private val domain = text(12f)
    private val sponsored = text(11f)
    private val age = text(11f)
    private val price = text(12f)
    private val warning = text(11f)
    private val action = text(13f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }
    private val feedback = ImageView(context).apply {
        setImageResource(android.R.drawable.ic_menu_more)
        contentDescription = "Сведения о рекламе"
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val media = MediaView(context)
    val hasVideo = ad.adAssets.media?.hasVideo == true
    private val mediaRatio = ad.adAssets.media?.aspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?: ad.adAssets.image?.let { image ->
            if (image.height > 0) image.width.toFloat() / image.height else 1f
        } ?: 1f
    private val warningArea = ad.adAssets.warning?.minimumRequiredArea?.coerceIn(0f, 0.9f) ?: 0f
    val bound: Boolean

    init {
        addView(adView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        adView.setPadding(dp(12), dp(10), dp(12), dp(10))
        val content = column()
        adView.addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
        header.addView(sponsored, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(age, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        header.addView(feedback, LinearLayout.LayoutParams(dp(32), dp(32)))
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val headline = row().apply { gravity = Gravity.CENTER_VERTICAL }
        if (!hasVideo) {
            headline.addView(media, LinearLayout.LayoutParams(dp(84), (dp(84) / mediaRatio).roundToInt().coerceAtLeast(1))
                .apply { marginEnd = dp(12) })
        }
        headline.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addView(headline, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        if (hasVideo) {
            content.addView(media, LinearLayout.LayoutParams(MATCH_PARENT, dp(160)).apply { topMargin = dp(8) })
        }
        val footer = LinearLayout(context).apply {
            orientation = if (resources.configuration.fontScale >= 1.4f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = column().apply {
            addView(domain, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(price, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val stacked = footer.orientation == LinearLayout.VERTICAL
        footer.addView(identity, if (stacked) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        footer.addView(action, LinearLayout.LayoutParams(if (stacked) MATCH_PARENT else WRAP_CONTENT, WRAP_CONTENT))
        content.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        content.addView(warning, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        val binder = NativeAdViewBinder.Builder(adView)
            .setTitleView(title).setDomainView(domain).setSponsoredView(sponsored).setAgeView(age)
            .setFeedbackView(feedback).setIconView(icon).setMediaView(media).setPriceView(price)
            .setWarningView(warning).setCallToActionView(action).build()
        bound = try {
            ad.bindNativeAd(binder) is AdBindingResult.Success
        } catch (_: Exception) {
            false
        }
        if (!bound) {
            visibility = View.GONE
            Log.w("AstroPhotoAds", "native_binding_failed")
        }
        clipToOutline = true
        adView.clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (hasVideo) {
            media.layoutParams.height = ((MeasureSpec.getSize(widthMeasureSpec) - dp(24)) / mediaRatio)
                .roundToInt().coerceAtLeast(dp(160))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (warning.visibility == View.VISIBLE && warningArea > 0f) {
            val nonWarningHeight = measuredHeight - warning.measuredHeight
            val required = (nonWarningHeight * warningArea / (1f - warningArea)).roundToInt()
            if (warning.minHeight != required) {
                warning.minHeight = required
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    fun applyPalette(surface: Int, primaryText: Int, secondaryText: Int, accent: Int, outline: Int) {
        adView.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), outline)
        }
        title.setTextColor(primaryText)
        listOf(domain, sponsored, age, price, warning).forEach { it.setTextColor(secondaryText) }
        action.setTextColor(accent)
        action.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), accent)
        }
        feedback.setColorFilter(secondaryText)
    }

    fun release() { adView.removeAllViews() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        visibility = View.GONE
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
