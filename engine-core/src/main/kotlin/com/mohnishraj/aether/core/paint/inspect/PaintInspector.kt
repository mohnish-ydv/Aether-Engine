package com.mohnishraj.aether.core.paint.inspect

import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.paint.DisplayList
import com.mohnishraj.aether.core.paint.PaintCommand
import java.util.Locale

object PaintInspector {
    fun displayList(list: DisplayList, maxCommands: Int = 500): String = buildString {
        appendLine("AETHER PAINT DISPLAY LIST")
        appendLine("=========================")
        appendLine("generation=${list.generation} viewport=${rect(list.viewport)} commands=${list.commandCount}")
        appendLine("text=${list.textCommandCount} images=${list.imageCommandCount} clips=${list.clipCommandCount} issues=${list.issues.size}")
        list.commands.take(maxCommands).forEachIndexed { index, command ->
            append(index.toString().padStart(5)).append("  ").appendLine(describe(command))
        }
        if (list.commandCount > maxCommands) appendLine("… ${list.commandCount - maxCommands} more commands")
        if (list.issues.isNotEmpty()) {
            appendLine("---------------- ISSUES")
            list.issues.take(100).forEach { appendLine("${it.severity} ${it.code} node=${it.nodeId ?: "-"} ${it.message}") }
        }
    }

    fun summary(list: DisplayList): String = "generation=${list.generation} commands=${list.commandCount} text=${list.textCommandCount} images=${list.imageCommandCount} clips=${list.clipCommandCount} issues=${list.issues.size} bounds=${list.visualBounds?.let(::rect) ?: "empty"}"

    private fun describe(command: PaintCommand): String = when (command) {
        is PaintCommand.PushClip -> "PUSH_CLIP node=${command.nodeId} rect=${rect(command.rect)} radius=${fmt(command.radii.topLeft)}"
        is PaintCommand.PopClip -> "POP_CLIP node=${command.nodeId}"
        is PaintCommand.DrawShadow -> "SHADOW node=${command.nodeId} rect=${rect(command.rect)} offset=${fmt(command.shadow.offsetX)},${fmt(command.shadow.offsetY)} blur=${fmt(command.shadow.blurRadius)} color=${command.shadow.color.toHex()} inset=${command.shadow.inset}"
        is PaintCommand.FillRect -> "FILL_RECT node=${command.nodeId} rect=${rect(command.rect)} color=${command.color.toHex()} opacity=${fmt(command.opacity)}"
        is PaintCommand.FillRoundedRect -> "FILL_ROUNDED node=${command.nodeId} rect=${rect(command.rect)} radius=${fmt(command.radii.topLeft)} color=${command.color.toHex()} opacity=${fmt(command.opacity)}"
        is PaintCommand.DrawLinearGradient -> "LINEAR_GRADIENT node=${command.nodeId} rect=${rect(command.rect)} ${command.startColor.toHex()}→${command.endColor.toHex()} angle=${fmt(command.angleDegrees)}"
        is PaintCommand.DrawBorder -> "BORDER node=${command.nodeId} rect=${rect(command.rect)} widths=${command.border.widths} styles=${command.border.styles.joinToString("/")}"
        is PaintCommand.DrawText -> "TEXT node=${command.nodeId} rect=${rect(command.rect)} baseline=${fmt(command.baselinePx)} size=${fmt(command.fontSizePx)} color=${command.color.toHex()} text=${quote(command.text)}"
        is PaintCommand.DrawImage -> "IMAGE node=${command.nodeId} rect=${rect(command.destination)} fit=${command.fit} src=${quote(command.source)} alt=${command.altText?.let(::quote) ?: "-"}"
    }

    private fun rect(value: LayoutRect): String = "[${fmt(value.x)},${fmt(value.y)} ${fmt(value.width)}×${fmt(value.height)}]"
    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"").take(120)}\""
}
