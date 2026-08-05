package com.mohnishraj.aether

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import androidx.core.graphics.PathParser
import androidx.core.graphics.withClip
import androidx.core.net.toUri
import com.mohnishraj.aether.core.browser.features.ImageFormat
import com.mohnishraj.aether.core.browser.features.ImageFormatDetector
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.paint.ImageFit
import com.mohnishraj.aether.core.paint.ImagePosition
import com.mohnishraj.aether.core.paint.PaintCommand
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Native image loader/decoder for Aether's Canvas renderer; no WebView is involved. */
internal object AndroidImagePainter {
    private const val MAX_CACHE_ENTRIES = 24
    private const val MAX_SVG_CACHE_ENTRIES = 16
    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    private const val MAX_DECODE_DIMENSION = 4096
    private const val FAILURE_RETRY_MILLIS = 30_000L

    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX_CACHE_ENTRIES
    }
    private val svgCache = object : LinkedHashMap<String, String>(MAX_SVG_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_SVG_CACHE_ENTRIES
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val scopeEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val loader = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "aether-image-loader").apply { isDaemon = true }
    }

    fun draw(
        canvas: Canvas,
        paint: Paint,
        command: PaintCommand.DrawImage,
        context: android.content.Context,
        network: NetworkRuntime? = null,
        onReady: () -> Unit = {}
    ): Boolean {
        if (command.destination.width <= 0.0 || command.destination.height <= 0.0) return false
        val target = command.destination.toRectF()
        if (command.lazy && !RectF.intersects(target, RectF(canvas.clipBounds))) return false
        val scope = scopeFor(network)
        val epoch = scopeEpochs.computeIfAbsent(scope) { AtomicLong() }.get()
        val key = cacheKey(scope, epoch, command.source)

        synchronized(svgCache) { svgCache[key] }?.let { return drawSvgMarkup(canvas, paint, command, it) }
        if (isInlineSvg(command.source)) {
            val markup = decodeSvg(command.source) ?: return false
            synchronized(svgCache) { svgCache[key] = markup }
            return drawSvgMarkup(canvas, paint, command, markup)
        }

        val bitmap = synchronized(bitmapCache) { bitmapCache[key] }
            ?: decodeLocal(command.source, context)?.also { decoded ->
                when (decoded) {
                    is DecodedImage.Raster -> synchronized(bitmapCache) { bitmapCache[key] = decoded.bitmap }
                    is DecodedImage.Svg -> synchronized(svgCache) { svgCache[key] = decoded.markup }
                }
            }?.let { decoded ->
                when (decoded) {
                    is DecodedImage.Raster -> decoded.bitmap
                    is DecodedImage.Svg -> return drawSvgMarkup(canvas, paint, command, decoded.markup)
                }
            }

        if (bitmap == null && isNetworkSource(command.source)) {
            scheduleNetworkLoad(command.source, key, scope, epoch, network, onReady)
            return false
        }
        if (bitmap == null) return false

        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.alpha = opacity(command.opacity)
        val (sourceRect, destinationRect) = fittedRects(bitmap.width, bitmap.height, target, command.fit, command.position)
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
        return true
    }

    private fun scheduleNetworkLoad(
        source: String,
        key: String,
        scope: String,
        epoch: Long,
        network: NetworkRuntime?,
        onReady: () -> Unit
    ) {
        if (network == null || failedUntil[key]?.let { it > System.currentTimeMillis() } == true || !pending.add(key)) return
        loader.execute {
            var loaded = false
            try {
                val request = NetworkRequest.Builder(source)
                    .maxResponseBytes(MAX_IMAGE_BYTES.toLong())
                    .tag("image-render")
                    .build()
                val result = network.client.execute(request)
                val bytes = when (result) {
                    is NetworkResult.Success -> result.value.takeIf { it.isSuccessful }?.body
                    is NetworkResult.Failure -> null
                }
                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                    failedUntil[key] = System.currentTimeMillis() + FAILURE_RETRY_MILLIS
                } else {
                    if (scopeEpochs[scope]?.get() == epoch) {
                        when (val decoded = decodeBytes(bytes)) {
                            is DecodedImage.Raster -> synchronized(bitmapCache) { bitmapCache[key] = decoded.bitmap }
                            is DecodedImage.Svg -> synchronized(svgCache) { svgCache[key] = decoded.markup }
                            null -> failedUntil[key] = System.currentTimeMillis() + FAILURE_RETRY_MILLIS
                        }
                        loaded = decodedAvailable(key)
                    }
                }
            } catch (_: RuntimeException) {
                failedUntil[key] = System.currentTimeMillis() + FAILURE_RETRY_MILLIS
            } finally {
                pending.remove(key)
                if (loaded && scopeEpochs[scope]?.get() == epoch) onReady()
            }
        }
    }

    private fun decodedAvailable(key: String): Boolean =
        synchronized(bitmapCache) { key in bitmapCache } || synchronized(svgCache) { key in svgCache }

    fun clear(network: NetworkRuntime?) {
        val scope = scopeFor(network)
        scopeEpochs.computeIfAbsent(scope) { AtomicLong() }.incrementAndGet()
        val prefix = "$scope|"
        synchronized(bitmapCache) { bitmapCache.keys.removeAll { it.startsWith(prefix) } }
        synchronized(svgCache) { svgCache.keys.removeAll { it.startsWith(prefix) } }
        pending.removeAll { it.startsWith(prefix) }
        failedUntil.keys.removeAll { it.startsWith(prefix) }
    }

    private fun scopeFor(network: NetworkRuntime?): String =
        if (network == null) "local" else "network@${System.identityHashCode(network)}"

    private fun cacheKey(scope: String, epoch: Long, source: String): String = "$scope|$epoch|$source"

    private fun isNetworkSource(source: String): Boolean =
        source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true)

    private fun decodeLocal(source: String, context: android.content.Context): DecodedImage? = runCatching {
        when {
            source.startsWith("data:", ignoreCase = true) -> decodeBytes(decodeDataUri(source) ?: return@runCatching null)
            source.startsWith("asset://", ignoreCase = true) -> {
                val path = source.removePrefix("asset://").trimStart('/')
                context.assets.open(path).use { input -> decodeBytes(input.readBytesLimited(MAX_IMAGE_BYTES)) }
            }
            source.startsWith("file:", ignoreCase = true) -> decodeFile(source.toUri().path)
            source.startsWith("/", ignoreCase = false) -> decodeFile(source)
            else -> null
        }
    }.getOrNull()

    private fun decodeFile(path: String?): DecodedImage? {
        val file = path?.let(::File)?.takeIf(File::isFile) ?: return null
        if (file.length() !in 1..MAX_IMAGE_BYTES.toLong()) return null
        return decodeBytes(file.readBytes())
    }

    private fun decodeDataUri(source: String): ByteArray? {
        val comma = source.indexOf(',')
        if (comma <= 0) return null
        val metadata = source.substring(0, comma)
        val payload = source.substring(comma + 1)
        val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, StandardCharsets.UTF_8.name()).toByteArray(StandardCharsets.UTF_8)
        }
        return bytes.takeIf { it.size <= MAX_IMAGE_BYTES }
    }

    private fun decodeBytes(bytes: ByteArray): DecodedImage? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val metadata = ImageFormatDetector.detect(bytes)
        if (metadata.format == ImageFormat.SVG) {
            return bytes.toString(StandardCharsets.UTF_8).takeIf { it.contains("<svg", ignoreCase = true) }?.let(DecodedImage::Svg)
        }
        if (metadata.format == ImageFormat.UNKNOWN) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION || bounds.outHeight / sample > MAX_DECODE_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(DecodedImage::Raster)
    }

    private fun fittedRects(
        width: Int,
        height: Int,
        target: RectF,
        fit: ImageFit,
        position: ImagePosition
    ): Pair<Rect?, RectF> {
        if (width <= 0 || height <= 0) return null to target
        val sourceAspect = width.toFloat() / height.toFloat()
        val targetAspect = target.width() / target.height().coerceAtLeast(0.001f)
        val x = position.clampedX.toFloat()
        val y = position.clampedY.toFloat()
        fun positionedRect(drawWidth: Float, drawHeight: Float): RectF {
            val left = target.left + (target.width() - drawWidth) * x
            val top = target.top + (target.height() - drawHeight) * y
            return RectF(left, top, left + drawWidth, top + drawHeight)
        }
        return when (fit) {
            ImageFit.FILL -> null to target
            ImageFit.CONTAIN -> {
                val scale = min(target.width() / width, target.height() / height)
                null to positionedRect(width * scale, height * scale)
            }
            ImageFit.COVER -> {
                val source = if (sourceAspect > targetAspect) {
                    val cropWidth = (height * targetAspect).toInt().coerceIn(1, width)
                    val left = ((width - cropWidth) * x).toInt().coerceIn(0, width - cropWidth)
                    Rect(left, 0, left + cropWidth, height)
                } else {
                    val cropHeight = (width / targetAspect).toInt().coerceIn(1, height)
                    val top = ((height - cropHeight) * y).toInt().coerceIn(0, height - cropHeight)
                    Rect(0, top, width, top + cropHeight)
                }
                source to target
            }
            ImageFit.NONE -> {
                val drawWidth = min(width.toFloat(), target.width())
                val drawHeight = min(height.toFloat(), target.height())
                null to positionedRect(drawWidth, drawHeight)
            }
            ImageFit.SCALE_DOWN -> if (width <= target.width() && height <= target.height()) {
                null to positionedRect(width.toFloat(), height.toFloat())
            } else fittedRects(width, height, target, ImageFit.CONTAIN, position)
        }
    }

    private fun isInlineSvg(source: String): Boolean =
        source.startsWith("data:image/svg", ignoreCase = true) || source.trimStart().startsWith("<svg", ignoreCase = true)

    /** SVG foundation: viewBox scaling plus bounded rect/circle primitives. */
    private fun drawSvgMarkup(canvas: Canvas, paint: Paint, command: PaintCommand.DrawImage, markup: String): Boolean {
        val target = command.destination.toRectF()
        val viewBox = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(markup)?.groupValues?.get(1)?.trim()?.split(Regex("[ ,]+"))?.mapNotNull(String::toFloatOrNull)
        val vbX = viewBox?.getOrNull(0) ?: 0f
        val vbY = viewBox?.getOrNull(1) ?: 0f
        val vbWidth = viewBox?.getOrNull(2)?.takeIf { it > 0f } ?: target.width()
        val vbHeight = viewBox?.getOrNull(3)?.takeIf { it > 0f } ?: target.height()
        val sx = target.width() / vbWidth.coerceAtLeast(0.001f)
        val sy = target.height() / vbHeight.coerceAtLeast(0.001f)
        var painted = false
        canvas.withClip(target) {
            translate(target.left, target.top)
            scale(sx, sy)
            translate(-vbX, -vbY)
            Regex("<rect\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(markup).take(128).forEach { match ->
                val attrs = match.groupValues[1]
                val x = attribute(attrs, "x") ?: 0f
                val y = attribute(attrs, "y") ?: 0f
                val width = attribute(attrs, "width") ?: return@forEach
                val height = attribute(attrs, "height") ?: return@forEach
                val color = svgColor(attributeText(attrs, "fill")) ?: return@forEach
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = opacity(command.opacity)
                drawRect(x, y, x + max(0f, width), y + max(0f, height), paint)
                painted = true
            }
            Regex("<circle\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(markup).take(128).forEach { match ->
                val attrs = match.groupValues[1]
                val cx = attribute(attrs, "cx") ?: 0f
                val cy = attribute(attrs, "cy") ?: 0f
                val radius = attribute(attrs, "r") ?: return@forEach
                val color = svgColor(attributeText(attrs, "fill")) ?: return@forEach
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = opacity(command.opacity)
                drawCircle(cx, cy, max(0f, radius), paint)
                painted = true
            }
            Regex("<path\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(markup).take(256).forEach { match ->
                val attrs = match.groupValues[1]
                val pathData = attributeText(attrs, "d") ?: return@forEach
                val path = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull() ?: return@forEach
                val fill = svgColor(attributeText(attrs, "fill"))
                val stroke = svgColor(attributeText(attrs, "stroke"))
                if (fill != null) {
                    paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = fill; paint.alpha = opacity(command.opacity)
                    drawPath(path, paint)
                    painted = true
                }
                if (stroke != null) {
                    paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = attribute(attrs, "stroke-width") ?: 1f; paint.color = stroke; paint.alpha = opacity(command.opacity)
                    drawPath(path, paint)
                    painted = true
                }
            }
            Regex("<(polygon|polyline)\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(markup).take(128).forEach { match ->
                val attrs = match.groupValues[2]
                val points = attributeText(attrs, "points")?.trim()?.split(Regex("[ ,]+"))?.mapNotNull(String::toFloatOrNull).orEmpty()
                if (points.size < 4) return@forEach
                val path = android.graphics.Path().apply {
                    moveTo(points[0], points[1])
                    var index = 2
                    while (index + 1 < points.size) { lineTo(points[index], points[index + 1]); index += 2 }
                    if (match.groupValues[1].equals("polygon", true)) close()
                }
                val color = svgColor(attributeText(attrs, "fill")) ?: return@forEach
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = opacity(command.opacity)
                drawPath(path, paint)
                painted = true
            }
        }
        return painted
    }

    private fun decodeSvg(source: String): String? = runCatching {
        if (source.trimStart().startsWith("<svg", ignoreCase = true)) return@runCatching source
        decodeDataUri(source)?.toString(StandardCharsets.UTF_8)
    }.getOrNull()

    private fun attribute(attributes: String, name: String): Float? = attributeText(attributes, name)?.removeSuffix("px")?.toFloatOrNull()
    private fun attributeText(attributes: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(attributes)?.groupValues?.get(1)

    private fun svgColor(value: String?): Int? {
        val text = value?.trim()?.lowercase() ?: return null
        if (text == "none") return null
        return when {
            Regex("#[0-9a-f]{6}").matches(text) -> 0xff000000.toInt() or text.drop(1).toInt(16)
            Regex("#[0-9a-f]{3}").matches(text) -> {
                val expanded = buildString { text.drop(1).forEach { append(it).append(it) } }
                0xff000000.toInt() or expanded.toInt(16)
            }
            text == "black" -> 0xff000000.toInt()
            text == "white" -> 0xffffffff.toInt()
            text == "red" -> 0xffff0000.toInt()
            text == "green" -> 0xff008000.toInt()
            text == "blue" -> 0xff0000ff.toInt()
            else -> null
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Image exceeds $limit bytes" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun com.mohnishraj.aether.core.layout.LayoutRect.toRectF(): RectF =
        RectF(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    private fun opacity(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)

    private sealed interface DecodedImage {
        data class Raster(val bitmap: Bitmap) : DecodedImage
        data class Svg(val markup: String) : DecodedImage
    }
}
