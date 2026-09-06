package com.example.domain.util

object FileNameSanitizer {

    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\r\n\t\u0000-\u001F\u007F]""")
    private val CONSECUTIVE_DOTS = Regex("""\.+""")
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitize(rawTitle: String, extension: String = "mp4"): String {
        val cleanExt = extension.trim().removePrefix(".").trimEnd('.', ' ')
        var sanitizedTitle = rawTitle
            .replace(ILLEGAL_CHARS, "_")
            .replace(CONSECUTIVE_DOTS, "_")
            .trim()
            .trim('_')
            .trim('.', ' ')

        // Guard reserved names
        val upper = sanitizedTitle.uppercase()
        if (RESERVED_NAMES.contains(upper) || sanitizedTitle == "." || sanitizedTitle == "..") {
            sanitizedTitle = "media_$sanitizedTitle"
        }

        // Multi-byte UTF-8 safe truncation (max 120 bytes for title stem to leave room for extension, total <= 125 chars)
        var finalTitle = if (sanitizedTitle.isBlank()) {
            "video_${System.currentTimeMillis()}"
        } else {
            truncateUtf8ToMaxBytes(sanitizedTitle, 120)
        }

        // Re-check trailing characters after truncation
        finalTitle = finalTitle.trimEnd('.', ' ', '_')
        if (finalTitle.isBlank()) {
            finalTitle = "video_${System.currentTimeMillis()}"
        }

        return if (cleanExt.isNotBlank()) "$finalTitle.$cleanExt" else finalTitle
    }

    private fun truncateUtf8ToMaxBytes(input: String, maxBytes: Int): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return input

        var end = 0
        var charIndex = 0
        while (charIndex < input.length) {
            val codePoint = input.codePointAt(charIndex)
            val charCount = Character.charCount(codePoint)
            val charBytes = input.substring(charIndex, charIndex + charCount).toByteArray(Charsets.UTF_8).size
            if (end + charBytes > maxBytes) break
            end += charBytes
            charIndex += charCount
        }
        return input.substring(0, charIndex)
    }

    fun generateUniqueFileName(baseName: String): String {
        val dotIndex = baseName.lastIndexOf('.')
        return if (dotIndex > 0) {
            val name = baseName.substring(0, dotIndex)
            val ext = baseName.substring(dotIndex)
            "${name}_${System.currentTimeMillis()}$ext"
        } else {
            "${baseName}_${System.currentTimeMillis()}"
        }
    }
}


