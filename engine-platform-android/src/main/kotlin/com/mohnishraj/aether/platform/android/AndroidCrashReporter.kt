package com.mohnishraj.aether.platform.android

import android.content.Context
import android.os.Build
import com.mohnishraj.aether.core.BuildInfo
import com.mohnishraj.aether.core.crash.CrashEnvelope
import com.mohnishraj.aether.core.crash.CrashEnvelopeCodec
import com.mohnishraj.aether.core.crash.CrashReporter
import java.io.File
import java.util.UUID

class AndroidCrashReporter(context: Context) : CrashReporter {
    private val crashDir = File(context.filesDir, "aether/crashes").apply { mkdirs() }
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    init { purgeLegacySyntheticSelfTests() }

    override fun capture(throwable: Throwable, context: Map<String, String>): CrashEnvelope {
        val deviceContext = linkedMapOf(
            "engineVersion" to BuildInfo.ENGINE_VERSION,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "abi" to Build.SUPPORTED_ABIS.joinToString(",")
        )
        deviceContext.putAll(context)
        val envelope = CrashEnvelope(
            id = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            exceptionType = throwable::class.java.name,
            message = throwable.message.orEmpty(),
            stackTrace = throwable.stackTraceToString(),
            context = deviceContext
        )
        val file = File(crashDir, "${envelope.timestampMillis}-${envelope.id}.crash")
        runCatching { file.writeText(CrashEnvelopeCodec.encode(envelope)) }
        trimTo(30)
        return envelope
    }

    override fun recent(limit: Int): List<CrashEnvelope> = crashFiles()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { file -> runCatching { file to CrashEnvelopeCodec.decode(file.readText()) }.getOrNull() }
        .filterNot { (file, envelope) ->
            val synthetic = envelope.context["synthetic"] == "true" && envelope.message == "synthetic self-test"
            if (synthetic) runCatching { file.delete() }
            synthetic
        }
        .take(limit.coerceAtLeast(0))
        .map { it.second }

    fun installAsDefaultHandler() {
        if (previousHandler != null) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            capture(throwable, mapOf("uncaught" to "true", "thread" to thread.name))
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun exportJson(): String = recent(30).joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJson() }

    private fun purgeLegacySyntheticSelfTests() {
        crashFiles().forEach { file ->
            val envelope = runCatching { CrashEnvelopeCodec.decode(file.readText()) }.getOrNull() ?: return@forEach
            if (envelope.context["synthetic"] == "true" && envelope.message == "synthetic self-test") {
                runCatching { file.delete() }
            }
        }
    }

    private fun crashFiles(): List<File> = crashDir.listFiles().orEmpty().filter { it.isFile && it.extension == "crash" }

    private fun trimTo(maxFiles: Int) {
        crashFiles().sortedByDescending { it.lastModified() }.drop(maxFiles).forEach(File::delete)
    }
}
