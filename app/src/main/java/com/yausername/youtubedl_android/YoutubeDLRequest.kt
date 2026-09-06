package com.yausername.youtubedl_android

class YoutubeDLRequest(val url: String) {
    private val options = mutableListOf<String>()

    fun addOption(option: String): YoutubeDLRequest {
        options.add(option)
        return this
    }

    fun addOption(option: String, value: String): YoutubeDLRequest {
        options.add(option)
        options.add(value)
        return this
    }

    fun addOption(option: String, value: Number): YoutubeDLRequest {
        options.add(option)
        options.add(value.toString())
        return this
    }

    fun getOptions(): List<String> = options
}
