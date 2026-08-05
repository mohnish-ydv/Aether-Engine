package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.layout.LayoutRect
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/** Immutable premultiplied-independent sRGB color used by the display list. */
data class PaintColor(val red: Int, val green: Int, val blue: Int, val alpha: Int = 255) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    }

    val isTransparent: Boolean get() = alpha == 0
    fun withAlpha(multiplier: Double): PaintColor = copy(alpha = (alpha * multiplier.coerceIn(0.0, 1.0)).toInt().coerceIn(0, 255))
    fun toArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    fun toHex(): String = if (alpha == 255) "#%02x%02x%02x".format(red, green, blue) else "#%02x%02x%02x%02x".format(red, green, blue, alpha)

    companion object {
        val TRANSPARENT = PaintColor(0, 0, 0, 0)
        val BLACK = PaintColor(0, 0, 0)
        val WHITE = PaintColor(255, 255, 255)
    }
}

data class CornerRadii(
    val topLeft: Double = 0.0,
    val topRight: Double = 0.0,
    val bottomRight: Double = 0.0,
    val bottomLeft: Double = 0.0
) {
    init {
        require(listOf(topLeft, topRight, bottomRight, bottomLeft).all { it >= 0.0 && it.isFinite() })
    }
    val isSquare: Boolean get() = topLeft == 0.0 && topRight == 0.0 && bottomRight == 0.0 && bottomLeft == 0.0
    fun clamped(rect: LayoutRect): CornerRadii {
        val limit = max(0.0, min(rect.width, rect.height) / 2.0)
        return CornerRadii(topLeft.coerceAtMost(limit), topRight.coerceAtMost(limit), bottomRight.coerceAtMost(limit), bottomLeft.coerceAtMost(limit))
    }
}

data class BorderPaint(
    val widths: com.mohnishraj.aether.core.layout.LayoutEdges,
    val colors: List<PaintColor>,
    val styles: List<String>,
    val radii: CornerRadii
) {
    init {
        require(colors.size == 4)
        require(styles.size == 4)
    }
    val visible: Boolean get() = widths.top > 0.0 || widths.right > 0.0 || widths.bottom > 0.0 || widths.left > 0.0
}

data class BoxShadowPaint(
    val offsetX: Double,
    val offsetY: Double,
    val blurRadius: Double,
    val spreadRadius: Double,
    val color: PaintColor,
    val inset: Boolean = false
) {
    init {
        require(listOf(offsetX, offsetY, blurRadius, spreadRadius).all { it.isFinite() })
        require(blurRadius >= 0.0)
    }
}

enum class ImageFit { FILL, CONTAIN, COVER, NONE, SCALE_DOWN }

data class ImagePosition(
    val xFraction: Double = 0.5,
    val yFraction: Double = 0.5
) {
    init {
        require(xFraction.isFinite() && yFraction.isFinite())
    }

    val clampedX: Double get() = xFraction.coerceIn(0.0, 1.0)
    val clampedY: Double get() = yFraction.coerceIn(0.0, 1.0)
}

enum class PaintIssueSeverity { WARNING, ERROR }

data class PaintIssue(
    val code: String,
    val message: String,
    val nodeId: Long? = null,
    val severity: PaintIssueSeverity = PaintIssueSeverity.WARNING
)

data class PaintLimits(
    val maxCommands: Int = 1_000_000,
    val maxClipDepth: Int = 256,
    val maxShadowsPerBox: Int = 16,
    val maxTextCharacters: Int = 5_000_000,
    val maxImageSourceChars: Int = 16_384,
    val maxCoordinatePx: Double = 10_000_000.0
) {
    init {
        require(maxCommands > 0 && maxClipDepth > 0 && maxShadowsPerBox > 0 && maxTextCharacters > 0 && maxImageSourceChars > 0)
        require(maxCoordinatePx > 0.0 && maxCoordinatePx.isFinite())
    }
}

sealed class PaintCommand {
    abstract val bounds: LayoutRect?
    abstract val nodeId: Long?

    data class PushClip(
        val rect: LayoutRect,
        val radii: CornerRadii = CornerRadii(),
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class PopClip(override val nodeId: Long? = null) : PaintCommand() { override val bounds: LayoutRect? = null }

    data class DrawShadow(
        val rect: LayoutRect,
        val radii: CornerRadii,
        val shadow: BoxShadowPaint,
        override val nodeId: Long? = null
    ) : PaintCommand() {
        override val bounds: LayoutRect = LayoutRect(
            rect.x + shadow.offsetX - shadow.blurRadius - shadow.spreadRadius,
            rect.y + shadow.offsetY - shadow.blurRadius - shadow.spreadRadius,
            max(0.0, rect.width + 2.0 * (shadow.blurRadius + shadow.spreadRadius)),
            max(0.0, rect.height + 2.0 * (shadow.blurRadius + shadow.spreadRadius))
        )
    }

    data class FillRect(
        val rect: LayoutRect,
        val color: PaintColor,
        val opacity: Double = 1.0,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class FillRoundedRect(
        val rect: LayoutRect,
        val radii: CornerRadii,
        val color: PaintColor,
        val opacity: Double = 1.0,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class DrawLinearGradient(
        val rect: LayoutRect,
        val startColor: PaintColor,
        val endColor: PaintColor,
        val angleDegrees: Double,
        val radii: CornerRadii = CornerRadii(),
        val opacity: Double = 1.0,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class DrawBorder(
        val rect: LayoutRect,
        val border: BorderPaint,
        val opacity: Double = 1.0,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class DrawText(
        val text: String,
        val rect: LayoutRect,
        val baselinePx: Double,
        val fontSizePx: Double,
        val color: PaintColor,
        val fontFamily: String,
        val fontWeight: Int,
        val italic: Boolean,
        val letterSpacingPx: Double = 0.0,
        val wordSpacingPx: Double = 0.0,
        val textDecoration: String = "none",
        val opacity: Double = 1.0,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = rect }

    data class DrawImage(
        val source: String,
        val destination: LayoutRect,
        val fit: ImageFit,
        val position: ImagePosition = ImagePosition(),
        val opacity: Double = 1.0,
        val altText: String? = null,
        val lazy: Boolean = false,
        override val nodeId: Long? = null
    ) : PaintCommand() { override val bounds: LayoutRect = destination }
}

class DisplayList internal constructor(
    commands: List<PaintCommand>,
    val issues: List<PaintIssue>,
    val viewport: LayoutRect,
    val generation: Long
) {
    val commands: List<PaintCommand> = Collections.unmodifiableList(commands.toList())
    val commandCount: Int get() = commands.size
    val textCommandCount: Int get() = commands.count { it is PaintCommand.DrawText }
    val imageCommandCount: Int get() = commands.count { it is PaintCommand.DrawImage }
    val clipCommandCount: Int get() = commands.count { it is PaintCommand.PushClip }
    val visualBounds: LayoutRect? by lazy {
        commands.mapNotNull { it.bounds }.fold<LayoutRect, LayoutRect?>(null) { acc, rect -> acc?.union(rect) ?: rect }
    }

    fun commandsAt(x: Double, y: Double): List<PaintCommand> {
        val point = com.mohnishraj.aether.core.layout.LayoutPoint(x, y)
        return commands.filter { command -> command.bounds?.contains(point) == true }
    }
}

data class PaintStatistics(
    val displayListsBuilt: Long,
    val commandsProduced: Long,
    val textCommandsProduced: Long,
    val imageCommandsProduced: Long,
    val issuesSeen: Long,
    val lastPaintMillis: Double
)
