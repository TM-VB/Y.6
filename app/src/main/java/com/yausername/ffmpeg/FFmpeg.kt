package com.yausername.ffmpeg

import android.content.Context
import java.io.File

class FFmpeg private constructor() {

    companion object {
        private val instance = FFmpeg()
        @JvmStatic
        fun getInstance(): FFmpeg = instance
    }

    var binDir: File? = null
        private set

    fun init(context: Context) {
        binDir = File(context.filesDir, "ffmpeg_bin")
        if (!binDir!!.exists()) {
            binDir!!.mkdirs()
        }
    }
}
