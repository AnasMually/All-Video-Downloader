package com.anas_mugally.videodownloader.domain

import java.net.URI

object UrlTools {
    private val webUrlPattern = Regex("""https?://[^\s<>\"']+""", RegexOption.IGNORE_CASE)

    fun extractHttpUrl(rawText: String): String? {
        val candidate = webUrlPattern.find(rawText.trim())?.value
            ?.trimEnd('.', ',', '،', ';')
            ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return candidate.takeIf {
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        }
    }
}
