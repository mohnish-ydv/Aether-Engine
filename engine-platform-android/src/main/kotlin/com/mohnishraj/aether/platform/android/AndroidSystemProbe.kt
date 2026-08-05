package com.mohnishraj.aether.platform.android

import android.os.Build

data class AndroidSystemSnapshot(
    val sdk: Int,
    val release: String,
    val device: String,
    val processors: Int,
    val abis: List<String>
)

object AndroidSystemProbe {
    fun snapshot() = AndroidSystemSnapshot(
        sdk = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        processors = Runtime.getRuntime().availableProcessors(),
        abis = Build.SUPPORTED_ABIS.toList()
    )
}
