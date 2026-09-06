package com.example.domain.validator

import java.net.URI

object UrlValidator {

    private val SUPPORTED_SCHEMES = setOf("http", "https")

    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\r")) return false

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host ?: return false

            SUPPORTED_SCHEMES.contains(scheme) && host.contains(".") && host.length >= 4
        } catch (_: Exception) {
            false
        }
    }

    fun isCommonVideoPlatform(url: String): Boolean {
        if (!isValidUrl(url)) return false
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
                lower.contains("youtu.be") ||
                lower.contains("tiktok.com") ||
                lower.contains("instagram.com") ||
                lower.contains("twitter.com") ||
                lower.contains("x.com") ||
                lower.contains("facebook.com") ||
                lower.contains("fb.watch") ||
                lower.contains("vimeo.com") ||
                lower.contains("dailymotion.com") ||
                lower.contains("reddit.com") ||
                lower.contains("twitch.tv")
    }
}
