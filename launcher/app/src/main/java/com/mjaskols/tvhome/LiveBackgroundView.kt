package com.mjaskols.tvhome

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

/**
 * "Live wallpaper": the M&M photo with a slow Ken Burns drift (zoom + pan).
 * Only view properties are animated, so the bitmap is never redrawn — the RenderThread
 * just recomposites one textured quad. Decoded as RGB_565 (~5.5 MB instead of ~11 MB).
 */
class LiveBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private val animators: List<ObjectAnimator>

    init {
        scaleType = ScaleType.CENTER_CROP
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.mm_photo, opts))
        val d = resources.displayMetrics.density
        // Base scale > 1 leaves slack so the pan never reveals an edge.
        animators = listOf(
            drift(View.SCALE_X, 1.08f, 1.16f, 41_000),
            drift(View.SCALE_Y, 1.08f, 1.16f, 41_000),
            drift(View.TRANSLATION_X, -28 * d, 28 * d, 57_000),
            drift(View.TRANSLATION_Y, -12 * d, 12 * d, 47_000),
        )
        scaleX = 1.08f
        scaleY = 1.08f
    }

    private fun drift(prop: android.util.Property<View, Float>, from: Float, to: Float, ms: Long) =
        ObjectAnimator.ofFloat(this, prop, from, to).apply {
            duration = ms
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

    fun start() = animators.forEach { if (it.isStarted) it.resume() else it.start() }

    fun stop() = animators.forEach { it.pause() }

    override fun onDetachedFromWindow() {
        animators.forEach { it.cancel() }
        super.onDetachedFromWindow()
    }
}
