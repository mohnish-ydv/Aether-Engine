package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.net.model.AetherUrl
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class AddressResolver(
    private val searchTemplate: String = "https://www.google.com/search?q=%s",
    private val homeUrl: String = HOME_URL
) {
    init {
        require("%s" in searchTemplate) { "Search template must contain %s" }
        AetherUrl.parse(homeUrl)
    }

    fun resolve(rawInput: String): ResolvedAddress {
        val input = rawInput.trim().take(MAX_INPUT_CHARS)
        if (input.isBlank() || input.equals("about:blank", ignoreCase = true) || input.equals("aether:newtab", ignoreCase = true)) {
            return ResolvedAddress(rawInput, homeUrl, "New tab", wasSearch = false, internal = true)
        }

        val suppliedScheme = SCHEME_PREFIX.find(input)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        require(suppliedScheme == null || suppliedScheme == "http" || suppliedScheme == "https") {
            "Unsupported or unsafe URL scheme: $suppliedScheme"
        }
        val explicit = runCatching { AetherUrl.parse(input).toString() }.getOrNull()
        if (explicit != null) {
            return ResolvedAddress(rawInput, explicit, explicit, wasSearch = false, internal = explicit == homeUrl)
        }

        val domainCandidate = input.lowercase(Locale.ROOT)
        if (looksLikeHost(domainCandidate)) {
            val url = runCatching { AetherUrl.parse("https://$input").toString() }.getOrNull()
            if (url != null) return ResolvedAddress(rawInput, url, url, wasSearch = false, internal = false)
        }

        val encoded = URLEncoder.encode(input, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val search = searchTemplate.format(encoded)
        return ResolvedAddress(rawInput, AetherUrl.parse(search).toString(), input, wasSearch = true, internal = false)
    }

    private fun looksLikeHost(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        val host = value.substringBefore('/').substringBefore(':')
        return host == "localhost" || host.matches(IPV4) || ('.' in host && host.none { it !in HOST_CHARS })
    }

    companion object {
        const val HOME_URL = "https://newtab.aether/"
        private const val MAX_INPUT_CHARS = 2_048
        private val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        private val HOST_CHARS = (('a'..'z') + ('0'..'9') + listOf('-', '.')).toSet()
    }
}
