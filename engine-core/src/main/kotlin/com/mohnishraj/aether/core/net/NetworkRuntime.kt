package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.net.cache.HttpCache
import com.mohnishraj.aether.core.net.cookie.CookieJar
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.download.DownloadManager

class NetworkRuntime(
    val client: NetworkClient,
    val downloads: DownloadManager,
    val dns: DnsResolver,
    val cookies: CookieJar,
    val cache: HttpCache
) {
    fun clearPrivateData() {
        cookies.clear()
        cache.clear()
    }
}
