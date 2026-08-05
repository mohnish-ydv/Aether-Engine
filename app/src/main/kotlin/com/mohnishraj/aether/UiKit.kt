package com.mohnishraj.aether

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object UiKit {
    const val BG = 0xFF090D14.toInt()
    const val SURFACE = 0xFF111827.toInt()
    const val SURFACE_2 = 0xFF172033.toInt()
    const val TEXT = 0xFFF5F7FA.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val PRIMARY = 0xFF65E6C4.toInt()
    const val SUCCESS = 0xFF70E39B.toInt()
    const val DANGER = 0xFFFF7B89.toInt()
    const val BORDER = 0xFF263348.toInt()

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun background(color: Int, radiusDp: Int, strokeColor: Int? = null, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setCornerRadius(context.dp(radiusDp).toFloat())
            strokeColor?.let { setStroke(context.dp(1), it) }
        }

    fun title(context: Context, text: String, size: Float = 24f) = TextView(context).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun body(context: Context, text: String, color: Int = MUTED, size: Float = 14f) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        setLineSpacing(0f, 1.15f)
    }

    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setAllCaps(false)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) BG else TEXT)
        background = UiKit.background(if (primary) PRIMARY else SURFACE_2, 14, if (primary) null else BORDER, context)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        minHeight = context.dp(48)
        setOnClickListener { onClick() }
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
        background = UiKit.background(SURFACE, 18, BORDER, context)
    }

    fun spacer(context: Context, heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(heightDp))
    }

    fun chip(context: Context, label: String, color: Int = PRIMARY) = TextView(context).apply {
        text = label
        setTextColor(color)
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
        background = UiKit.background(SURFACE_2, 20, color, context)
    }
}
