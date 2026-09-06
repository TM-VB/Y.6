package com.example.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.example.DownloadVideosApplication
import com.example.data.local.AppDatabase
import com.example.domain.model.DownloadError
import com.example.ui.downloads.DownloadFilter
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.downloads.HistorySortOption
import com.example.ui.home.HomeUiState
import com.example.ui.home.HomeViewModel
import com.example.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelsComprehensiveTest {

    private lateinit var application: DownloadVideosApplication

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        AppDatabase.getInstance(application).clearAllTables()
    }

    // --- HomeViewModel Tests ---

    @Test
    fun testHomeViewModel_InitialStateIsIdle() {
        val viewModel = HomeViewModel(application)
        assertEquals("", viewModel.urlInput.value)
        assertEquals(HomeUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.duplicateWarningTask.value)
    }

    @Test
    fun testHomeViewModel_UrlChangeUpdatesInput() {
        val viewModel = HomeViewModel(application)
        viewModel.onUrlChange("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", viewModel.urlInput.value)
    }

    @Test
    fun testHomeViewModel_EmptyUrlAnalysisEmitsErrorState() {
        val viewModel = HomeViewModel(application)
        viewModel.onUrlChange("")
        viewModel.analyzeUrl()

        val state = viewModel.uiState.value
        assertTrue("State should be Error when URL is empty", state is HomeUiState.Error)
        val errorState = state as HomeUiState.Error
        assertTrue("Error should be InvalidUrl", errorState.error is DownloadError.InvalidUrl)
    }

    @Test
    fun testHomeViewModel_UrlChangeResetsErrorStateToIdle() {
        val viewModel = HomeViewModel(application)
        viewModel.onUrlChange("")
        viewModel.analyzeUrl()
        assertTrue(viewModel.uiState.value is HomeUiState.Error)

        viewModel.onUrlChange("https://example.com/video")
        assertEquals(HomeUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun testHomeViewModel_CancelAnalysisRestoresIdle() {
        val viewModel = HomeViewModel(application)
        viewModel.onUrlChange("https://www.youtube.com/watch?v=test")
        viewModel.cancelAnalysis()
        assertEquals(HomeUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun testHomeViewModel_DismissDuplicateWarning() {
        val viewModel = HomeViewModel(application)
        viewModel.dismissDuplicateWarning()
        assertNull(viewModel.duplicateWarningTask.value)
    }

    // --- DownloadsViewModel Tests ---

    @Test
    fun testDownloadsViewModel_InitialState() {
        val viewModel = DownloadsViewModel(application)
        assertEquals(DownloadFilter.ALL, viewModel.selectedFilter.value)
        assertEquals("", viewModel.searchQuery.value)
        assertEquals(HistorySortOption.NEWEST, viewModel.sortOption.value)
        assertNull(viewModel.selectedDetailTask.value)
        assertTrue(viewModel.selectedTaskIds.value.isEmpty())
    }

    @Test
    fun testDownloadsViewModel_FilterSelection() {
        val viewModel = DownloadsViewModel(application)
        viewModel.setFilter(DownloadFilter.ACTIVE)
        assertEquals(DownloadFilter.ACTIVE, viewModel.selectedFilter.value)

        viewModel.setFilter(DownloadFilter.COMPLETED)
        assertEquals(DownloadFilter.COMPLETED, viewModel.selectedFilter.value)

        viewModel.setFilter(DownloadFilter.FAILED)
        assertEquals(DownloadFilter.FAILED, viewModel.selectedFilter.value)
    }

    @Test
    fun testDownloadsViewModel_SearchQuery() {
        val viewModel = DownloadsViewModel(application)
        viewModel.setSearchQuery("Kotlin Tutorial")
        assertEquals("Kotlin Tutorial", viewModel.searchQuery.value)

        viewModel.setSearchQuery("")
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun testDownloadsViewModel_SortOptionSelection() {
        val viewModel = DownloadsViewModel(application)
        viewModel.setSortOption(HistorySortOption.NAME)
        assertEquals(HistorySortOption.NAME, viewModel.sortOption.value)

        viewModel.setSortOption(HistorySortOption.SIZE)
        assertEquals(HistorySortOption.SIZE, viewModel.sortOption.value)

        viewModel.setSortOption(HistorySortOption.OLDEST)
        assertEquals(HistorySortOption.OLDEST, viewModel.sortOption.value)
    }

    @Test
    fun testDownloadsViewModel_MultiSelectionMode() {
        val viewModel = DownloadsViewModel(application)

        viewModel.toggleTaskSelection("task_1")
        assertTrue(viewModel.selectedTaskIds.value.contains("task_1"))

        viewModel.toggleTaskSelection("task_2")
        assertEquals(2, viewModel.selectedTaskIds.value.size)

        viewModel.toggleTaskSelection("task_1")
        assertEquals(1, viewModel.selectedTaskIds.value.size)
        assertFalse(viewModel.selectedTaskIds.value.contains("task_1"))
        assertTrue(viewModel.selectedTaskIds.value.contains("task_2"))

        viewModel.clearSelection()
        assertTrue(viewModel.selectedTaskIds.value.isEmpty())
    }

    // --- SettingsViewModel Tests ---

    @Test
    fun testSettingsViewModel_InitialStateAndDialogToggles() {
        val viewModel = SettingsViewModel(application)
        assertFalse(viewModel.showLogsDialog.value)
        assertFalse(viewModel.showCookiesDialog.value)

        viewModel.openLogs()
        assertTrue(viewModel.showLogsDialog.value)

        viewModel.closeLogs()
        assertFalse(viewModel.showLogsDialog.value)

        viewModel.openCookiesDialog()
        assertTrue(viewModel.showCookiesDialog.value)

        viewModel.closeCookiesDialog()
        assertFalse(viewModel.showCookiesDialog.value)
    }

    @Test
    fun testSettingsViewModel_ClearStatusMessage() {
        val viewModel = SettingsViewModel(application)
        viewModel.clearStatusMessage()
        val state = viewModel.uiState.value
        assertNull(state.statusMessage)
    }
}
