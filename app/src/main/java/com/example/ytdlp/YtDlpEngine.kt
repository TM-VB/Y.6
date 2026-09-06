package com.example.ytdlp

import android.content.Context
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.engine.YtDlpMediaEngine
import kotlinx.coroutines.runBlocking

/**
 * Backward-compatible facade delegating all operations to the single source of truth:
 * [YtDlpDownloadEngine] (implementing [YtDlpMediaEngine]).
 *
 * All duplicate implementations of download, cookies, and process lifecycle
 * have been unified into [YtDlpDownloadEngine].
 *
 * This facade explicitly requires an Application [Context] passed via [init]
 * or directly to the query methods, eliminating all unsafe hidden API reflection.
 */
object YtDlpEngine {

    @Volatile
    private var delegateEngine: YtDlpMediaEngine? = null

    /**
     * Explicitly sets or mocks the delegate engine for dependency injection or testing.
     */
    fun setEngine(engine: YtDlpMediaEngine) {
        delegateEngine = engine
    }

    /**
     * Resets the internal delegate engine (useful for test isolation).
     */
    fun reset() {
        delegateEngine = null
    }

    /**
     * Internal resolver for the active media engine.
     * Uses the already initialized delegate, or initializes one using the provided [context].
     * Never uses reflection or internal non-SDK interfaces.
     */
    private fun getEngine(context: Context? = null): YtDlpMediaEngine {
        val current = delegateEngine
        if (current != null) return current

        return synchronized(this) {
            delegateEngine ?: run {
                val appCtx = context?.applicationContext
                if (appCtx != null) {
                    YtDlpDownloadEngine.getInstance(appCtx).also { delegateEngine = it }
                } else {
                    throw IllegalStateException(
                        "YtDlpEngine requires Context for initialization. Call YtDlpEngine.init(context) or pass a Context explicitly."
                    )
                }
            }
        }
    }

    /**
     * Initializes the underlying yt-dlp native environment using an explicit Application Context.
     */
    fun init(context: Context): Result<Unit> {
        val appCtx = context.applicationContext
        val engine = synchronized(this) {
            delegateEngine ?: YtDlpDownloadEngine.getInstance(appCtx).also {
                delegateEngine = it
            }
        }
        return engine.init(appCtx)
    }

    fun isReady(): Boolean = delegateEngine?.isReady() ?: false

    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist") || lower.contains("/sets/")
    }

    suspend fun extractPlaylist(
        url: String,
        context: Context? = null,
        processId: String? = null
    ): Result<PlaylistInfo> {
        return getEngine(context).extractPlaylist(url, processId)
    }

    suspend fun extractInfo(
        url: String,
        processId: String? = null,
        context: Context? = null
    ): Result<VideoInfo> {
        return getEngine(context).extractInfo(url, processId)
    }

    suspend fun getFormats(
        url: String,
        processId: String? = null,
        context: Context? = null
    ): Result<List<FormatInfo>> {
        return getEngine(context).getFormats(url, processId)
    }

    fun fetchVideoInfo(url: String, context: Context? = null): Result<VideoMetadata> {
        return runBlocking {
            getEngine(context).fetchVideoInfo(url)
        }
    }

    fun cancel(processId: String) {
        runBlocking {
            try {
                delegateEngine?.cancel(processId)
            } catch (_: Throwable) {}
        }
    }

    fun getVersion(context: Context): String {
        return getEngine(context).getVersion(context.applicationContext)
    }

    fun updateEngine(context: Context): Result<String> {
        return runBlocking {
            getEngine(context).updateEngine(context.applicationContext)
        }
    }
}

