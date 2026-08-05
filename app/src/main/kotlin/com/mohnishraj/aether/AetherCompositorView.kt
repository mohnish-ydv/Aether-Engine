package com.mohnishraj.aether

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.paint.CornerRadii
import com.mohnishraj.aether.core.paint.PaintColor
import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.render.CompositorLayer
import com.mohnishraj.aether.core.render.LayerPaintItem
import com.mohnishraj.aether.core.render.RenderFrame
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Native Canvas consumer for M9 compositor frames. It never delegates page rendering to WebView. */
class AetherCompositorView(context: Context) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame: RenderFrame? = null
    private var lastTouchY = 0f
    private var contentScale = 1f
    var onScrollBy: ((dx: Double, dy: Double) -> Unit)? = null
    var onViewportChanged: ((widthCssPx: Double, heightCssPx: Double) -> Unit)? = null
    var imageNetwork: NetworkRuntime? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = context.getString(R.string.compositor_preview_description)
        isFocusable = true
        isClickable = true
    }

    fun submit(value: RenderFrame) {
        frame = value
        // Android deprecated partial View invalidation because hardware rendering may
        // ignore dirty rectangles. The engine still computes and reuses precise damage
        // regions internally; schedule one frame-safe redraw for the native consumer.
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        onViewportChanged?.invoke(width / density.toDouble(), height / density.toDouble())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rendered = frame ?: return
        contentScale = scaleFor(rendered)
        val dx = (width - rendered.viewport.widthPx.toFloat() * contentScale) / 2f
        canvas.withSave {
            clipRect(0f, 0f, width.toFloat(), height.toFloat())
            withTranslation(dx, 0f) {
                scale(contentScale, contentScale)
                rendered.composition.layers.forEach { layer -> drawLayer(this, layer) }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (lastTouchY - event.y) / contentScale.coerceAtLeast(0.01f)
                lastTouchY = event.y
                if (delta != 0f) onScrollBy?.invoke(0.0, delta.toDouble())
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawLayer(canvas: Canvas, layer: CompositorLayer) {
        canvas.withTranslation(
            layer.transform.translateX.toFloat(),
            layer.transform.translateY.toFloat(),
        ) {
            layer.clip?.let { clipRect(it.toRectF()) }
            layer.items.forEach { item -> drawItem(this, item) }
        }
    }

    private fun drawItem(canvas: Canvas, item: LayerPaintItem) {
        canvas.withSave {
            item.clips.forEach { clip ->
                if (clip.radii.isSquare) clipRect(clip.rect.toRectF())
                else clipPath(roundedPath(clip.rect.toRectF(), clip.radii))
            }
            when (val command = item.command) {
                is PaintCommand.PushClip, is PaintCommand.PopClip -> Unit
                is PaintCommand.DrawShadow -> drawShadow(this, command)
                is PaintCommand.FillRect -> {
                    prepareFill(command.color, command.opacity)
                    drawRect(command.rect.toRectF(), brush)
                }
                is PaintCommand.FillRoundedRect -> {
                    prepareFill(command.color, command.opacity)
                    drawPath(roundedPath(command.rect.toRectF(), command.radii), brush)
                }
                is PaintCommand.DrawLinearGradient -> drawGradient(this, command)
                is PaintCommand.DrawBorder -> drawBorder(this, command)
                is PaintCommand.DrawText -> drawText(this, command)
                is PaintCommand.DrawImage -> if (!AndroidImagePainter.draw(this, brush, command, context, imageNetwork) { postInvalidateOnAnimation() }) drawImagePlaceholder(this, command)
            }
        }
    }

    private fun drawShadow(canvas: Canvas, command: PaintCommand.DrawShadow) {
        val shadow = command.shadow
        brush.reset()
        brush.isAntiAlias = true
        brush.style = Paint.Style.FILL
        brush.color = shadow.color.toArgb()
        brush.setShadowLayer(shadow.blurRadius.toFloat(), shadow.offsetX.toFloat(), shadow.offsetY.toFloat(), shadow.color.toArgb())
        val spread = shadow.spreadRadius.toFloat()
        val rect = command.rect.toRectF().apply { inset(-spread, -spread) }
        canvas.drawPath(roundedPath(rect, command.radii), brush)
        brush.clearShadowLayer()
    }

    private fun drawGradient(canvas: Canvas, command: PaintCommand.DrawLinearGradient) {
        val rect = command.rect.toRectF()
        val radians = Math.toRadians(command.angleDegrees - 90.0)
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val radius = max(rect.width(), rect.height()) / 2f
        brush.reset()
        brush.isAntiAlias = true
        brush.style = Paint.Style.FILL
        brush.alpha = alpha(command.opacity)
        brush.shader = LinearGradient(
            centerX - cos(radians).toFloat() * radius,
            centerY - sin(radians).toFloat() * radius,
            centerX + cos(radians).toFloat() * radius,
            centerY + sin(radians).toFloat() * radius,
            command.startColor.toArgb(),
            command.endColor.toArgb(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(roundedPath(rect, command.radii), brush)
        brush.shader = null
    }

    private fun drawBorder(canvas: Canvas, command: PaintCommand.DrawBorder) {
        val rect = command.rect.toRectF()
        val widths = command.border.widths
        val colors = command.border.colors
        val styles = command.border.styles
        fun side(width: Double, style: String, color: PaintColor, draw: () -> Unit) {
            if (width <= 0.0 || style == "none" || style == "hidden") return
            brush.reset()
            brush.isAntiAlias = true
            brush.style = Paint.Style.STROKE
            brush.strokeWidth = width.toFloat()
            brush.color = color.toArgb()
            brush.alpha = alpha(command.opacity)
            draw()
        }
        side(widths.top, styles[0], colors[0]) { canvas.drawLine(rect.left, rect.top + widths.top.toFloat() / 2f, rect.right, rect.top + widths.top.toFloat() / 2f, brush) }
        side(widths.right, styles[1], colors[1]) { canvas.drawLine(rect.right - widths.right.toFloat() / 2f, rect.top, rect.right - widths.right.toFloat() / 2f, rect.bottom, brush) }
        side(widths.bottom, styles[2], colors[2]) { canvas.drawLine(rect.left, rect.bottom - widths.bottom.toFloat() / 2f, rect.right, rect.bottom - widths.bottom.toFloat() / 2f, brush) }
        side(widths.left, styles[3], colors[3]) { canvas.drawLine(rect.left + widths.left.toFloat() / 2f, rect.top, rect.left + widths.left.toFloat() / 2f, rect.bottom, brush) }
    }

    private fun drawText(canvas: Canvas, command: PaintCommand.DrawText) {
        brush.reset()
        brush.isAntiAlias = true
        brush.style = Paint.Style.FILL
        brush.color = command.color.toArgb()
        brush.alpha = alpha(command.opacity)
        brush.textSize = command.fontSizePx.toFloat()
        brush.typeface = Typeface.create(
            command.fontFamily,
            when {
                command.fontWeight >= 600 && command.italic -> Typeface.BOLD_ITALIC
                command.fontWeight >= 600 -> Typeface.BOLD
                command.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
        brush.letterSpacing = if (command.fontSizePx > 0.0) (command.letterSpacingPx / command.fontSizePx).toFloat() else 0f
        val decoration = command.textDecoration.lowercase()
        brush.isUnderlineText = "underline" in decoration
        brush.isStrikeThruText = "line-through" in decoration
        val startX = command.rect.x.toFloat()
        val baseline = (command.rect.y + command.baselinePx).toFloat()
        if (command.wordSpacingPx == 0.0) {
            canvas.drawText(command.text, startX, baseline, brush)
        } else {
            var x = startX
            command.text.forEach { character ->
                val part = character.toString()
                canvas.drawText(part, x, baseline, brush)
                x += brush.measureText(part) + if (character.isWhitespace()) command.wordSpacingPx.toFloat() else 0f
            }
        }
        if ("overline" in decoration) {
            val lineY = (command.rect.y + command.fontSizePx * 0.12).toFloat()
            canvas.drawLine(startX, lineY, startX + command.rect.width.toFloat(), lineY, brush)
        }
    }

    private fun drawImagePlaceholder(canvas: Canvas, command: PaintCommand.DrawImage) {
        val rect = command.destination.toRectF()
        brush.reset()
        brush.isAntiAlias = true
        brush.style = Paint.Style.FILL
        brush.color = 0xff172033.toInt()
        brush.alpha = alpha(command.opacity)
        canvas.drawRect(rect, brush)
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = 1f
        brush.color = 0xff55e6c1.toInt()
        canvas.drawRect(rect, brush)
        brush.style = Paint.Style.FILL
        brush.textSize = min(12f, rect.height() / 3f)
        brush.typeface = Typeface.MONOSPACE
        val label = command.altText?.takeIf(String::isNotBlank) ?: "IMAGE"
        canvas.drawText(label.take(24), rect.left + 4f, rect.centerY() + brush.textSize / 3f, brush)
    }

    private fun prepareFill(color: PaintColor, opacity: Double) {
        brush.reset()
        brush.isAntiAlias = true
        brush.style = Paint.Style.FILL
        brush.color = color.toArgb()
        brush.alpha = alpha(opacity)
    }

    private fun scaleFor(rendered: RenderFrame): Float =
        min(width / rendered.viewport.widthPx.toFloat(), height / rendered.viewport.heightPx.toFloat()).coerceAtLeast(0.01f)

    private fun roundedPath(rect: RectF, radii: CornerRadii): Path = Path().apply {
        addRoundRect(rect, floatArrayOf(
            radii.topLeft.toFloat(), radii.topLeft.toFloat(),
            radii.topRight.toFloat(), radii.topRight.toFloat(),
            radii.bottomRight.toFloat(), radii.bottomRight.toFloat(),
            radii.bottomLeft.toFloat(), radii.bottomLeft.toFloat()
        ), Path.Direction.CW)
    }

    private fun LayoutRect.toRectF(): RectF = RectF(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())
    private fun alpha(opacity: Double): Int = (opacity.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
}
