package com.mohnishraj.aether.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mohnishraj.aether.core.browser.ClipboardPort

class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    override fun readText(): String = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(appContext)
        ?.toString()
        .orEmpty()

    override fun writeText(value: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Aether", value))
    }
}
