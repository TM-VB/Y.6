package com.yausername.youtubedl_android.mapper

data class VideoInfo(
    var id: String? = null,
    var title: String? = null,
    var uploader: String? = null,
    var uploaderId: String? = null,
    var duration: Int = 0,
    var thumbnail: String? = null,
    var webpageUrl: String? = null,
    var description: String? = null,
    var extractor: String? = null,
    var formats: List<VideoFormat>? = null
)
