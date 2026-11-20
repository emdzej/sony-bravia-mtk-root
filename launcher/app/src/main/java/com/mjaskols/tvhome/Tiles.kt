package com.mjaskols.tvhome

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Focusable D-pad tile: title + optional subtitle, scales up when focused. */
class Tile(context: Context, widthRes: Int, heightRes: Int) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 17f
        setTextColor(0xFFF4EDE4.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
    }
    private val subtitle = TextView(context).apply {
        textSize = 13f
        setTextColor(0xCCF4EDE4.toInt())
        maxLines = 1
        visibility = View.GONE
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM or Gravity.START
        val pad = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        setBackgroundResource(R.drawable.tile_bg)
        isFocusable = true
        isClickable = true
        addView(title)
        addView(subtitle)
        layoutParams = MarginLayoutParams(
            resources.getDimensionPixelSize(widthRes),
            resources.getDimensionPixelSize(heightRes),
        ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.tile_gap) }
        setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    fun bind(title: CharSequence, subtitle: CharSequence? = null, dimmed: Boolean = false): Tile {
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        alpha = if (dimmed) 0.45f else 1f
        return this
    }
}
