package com.yausername.youtubedl_android.mapper

data class VideoFormat(
    var formatId: String? = null,
    var formatNote: String? = null,
    var ext: String? = null,
    var url: String? = null,
    var resolution: String? = null,
    var width: Int = 0,
    var height: Int = 0,
    var fps: Int = 0,
    var vcodec: String? = null,
    var acodec: String? = null,
    var tbr: Float = 0f,
    var abr: Float = 0f,
    var fileSize: Long = 0L,
    var fileSizeApprox: Long = 0L,
    var protocol: String? = null
)
