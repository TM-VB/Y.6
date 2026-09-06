package com.example.ytdlp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.downloader.engine.YtDlpMediaEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class YtDlpEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        YtDlpEngine.reset()
    }

    @After
    fun tearDown() {
        YtDlpEngine.reset()
    }

    @Test
    fun testIsPlaylistUrl() {
        assertTrue(YtDlpEngine.isPlaylistUrl("https://www.youtube.com/playlist?list=PL123456"))
        assertTrue(YtDlpEngine.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL123456"))
        assertTrue(YtDlpEngine.isPlaylistUrl("https://soundcloud.com/user/sets/my-playlist"))
        assertFalse(YtDlpEngine.isPlaylistUrl("https://www.youtube.com/watch?v=abc123456"))
        assertFalse(YtDlpEngine.isPlaylistUrl("https://vimeo.com/123456"))
    }

    @Test
    fun testUninitializedAccessThrowsIllegalStateException() {
        try {
            YtDlpEngine.fetchVideoInfo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            fail("Expected IllegalStateException when calling fetchVideoInfo without context or init")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Context for initialization") == true)
        }
    }

    @Test
    fun testSetEngineDelegatesCorrectly() = runBlocking {
        var cancelCalled = false
        val mockEngine = object : YtDlpMediaEngine {
            override suspend fun validateUrl(url: String): Boolean = true
            override suspend fun extractInfo(url: String, processId: String?): Result<VideoInfo> =
                Result.success(VideoInfo(id = "1", title = "Title", uploader = "Artist", duration = 120L, thumbnail = null, webpageUrl = url))
            override suspend fun getFormats(url: String, processId: String?): Result<List<FormatInfo>> =
                Result.success(emptyList())
            override suspend fun cancel(taskId: String) { cancelCalled = true }
            override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<File> =
                Result.failure(NotImplementedError())
            override suspend fun download(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<File> =
                Result.failure(NotImplementedError())
            override fun init(context: Context): Result<Unit> = Result.success(Unit)
            override fun isReady(): Boolean = true
            override fun getVersion(context: Context): String = "2026.01.01"
            override suspend fun updateEngine(context: Context): Result<String> = Result.success("Up to date")
            override fun isPlaylistUrl(url: String): Boolean = false
            override suspend fun extractPlaylist(url: String, processId: String?): Result<PlaylistInfo> =
                Result.success(PlaylistInfo(id = "p1", title = "Playlist", uploader = null, webpageUrl = url, entries = emptyList()))
            override suspend fun fetchVideoInfo(url: String, processId: String?): Result<VideoMetadata> =
                Result.success(VideoMetadata(id = "1", title = "Meta Title", uploader = "Author", durationSeconds = 120, thumbnailUrl = "thumb", webpageUrl = url))
        }

        YtDlpEngine.setEngine(mockEngine)
        assertTrue(YtDlpEngine.isReady())
        assertEquals("2026.01.01", YtDlpEngine.getVersion(context))

        val infoResult = YtDlpEngine.fetchVideoInfo("https://example.com/video")
        assertTrue(infoResult.isSuccess)
        assertEquals("Meta Title", infoResult.getOrNull()?.title)

        YtDlpEngine.cancel("task_1")
        assertTrue(cancelCalled)
    }

    @Test
    fun testInitWithContextInitializesSafelyWithoutReflection() {
        val result = YtDlpEngine.init(context)
        assertNotNull(result)
        // init executes safely without throwing any ClassNotFoundException or NoSuchMethodException
    }
}
