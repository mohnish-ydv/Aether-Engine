package com.mohnishraj.aether.platform.android

import android.util.Log
import com.mohnishraj.aether.core.log.LogEntry
import com.mohnishraj.aether.core.log.LogLevel
import com.mohnishraj.aether.core.log.LogSink

class AndroidLogSink : LogSink {
    override fun accept(entry: LogEntry) {
        val tag = "Aether:${entry.tag}".take(23)
        when (entry.level) {
            LogLevel.TRACE, LogLevel.DEBUG -> Log.d(tag, entry.message)
            LogLevel.INFO -> Log.i(tag, entry.message)
            LogLevel.WARN -> Log.w(tag, entry.message)
            LogLevel.ERROR -> Log.e(tag, entry.message)
        }
    }
}
