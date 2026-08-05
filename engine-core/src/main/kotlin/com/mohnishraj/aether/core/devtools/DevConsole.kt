package com.mohnishraj.aether.core.devtools

import com.mohnishraj.aether.core.EngineRuntime
import java.util.Locale

data class CommandResult(
    val success: Boolean,
    val output: String,
    val clearScreen: Boolean = false
) {
    companion object {
        fun ok(output: String = "OK") = CommandResult(true, output)
        fun error(output: String) = CommandResult(false, output)
        fun clear() = CommandResult(true, "", clearScreen = true)
    }
}

fun interface DevCommand {
    fun execute(context: CommandContext, args: List<String>): CommandResult
}

data class CommandContext(val runtime: EngineRuntime)

class DevConsole {
    private val commands = linkedMapOf<String, Pair<String, DevCommand>>()

    @Synchronized fun register(name: String, description: String, command: DevCommand) {
        val key = name.lowercase(Locale.ROOT)
        require(key.matches(Regex("[a-z][a-z0-9_-]*"))) { "Invalid command name: $name" }
        commands[key] = description to command
    }

    @Synchronized fun names(): List<String> = commands.keys.toList()
    @Synchronized fun descriptions(): Map<String, String> = commands.mapValues { it.value.first }

    fun execute(context: CommandContext, line: String): CommandResult {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return CommandResult.ok("")
        val name = tokens.first().lowercase(Locale.ROOT)
        val command = synchronized(this) { commands[name]?.second }
            ?: return CommandResult.error("Unknown command '$name'. Type 'help'.")
        return runCatching { command.execute(context, tokens.drop(1)) }
            .getOrElse { CommandResult.error("${it::class.java.simpleName}: ${it.message}") }
    }

    companion object {
        fun tokenize(line: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var escaped = false
            line.forEach { ch ->
                when {
                    escaped -> { current.append(ch); escaped = false }
                    ch == '\\' -> escaped = true
                    quote != null && ch == quote -> quote = null
                    quote != null -> current.append(ch)
                    ch == '"' || ch == '\'' -> quote = ch
                    ch.isWhitespace() -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                    else -> current.append(ch)
                }
            }
            if (escaped) current.append('\\')
            require(quote == null) { "Unclosed quote" }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }
    }
}
