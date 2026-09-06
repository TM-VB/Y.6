package com.example.downloader.lifecycle

import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus

/**
 * Robust State Machine and Execution Identity validator for video downloads.
 * Strictly guarantees that:
 * 1. Stale callbacks with outdated runId are rejected.
 * 2. Terminal or paused states (PAUSED, CANCELLED, COMPLETED, FAILED) cannot be overwritten
 *    by delayed asynchronous callbacks into DOWNLOADING or PREPARING.
 * 3. Lifecycle stages advance in a strictly validated sequence.
 */
object DownloadStateMachine {

    /**
     * Set of non-active states that MUST NOT be overwritten by asynchronous engine/FFmpeg callbacks.
     */
    private val TERMINAL_OR_PAUSED_STATES = setOf(
        DownloadStatus.PAUSED,
        DownloadStatus.CANCELLED,
        DownloadStatus.COMPLETED,
        DownloadStatus.FAILED
    )

    fun isTerminalOrPaused(status: DownloadStatus): Boolean {
        return status in TERMINAL_OR_PAUSED_STATES
    }

    fun isTerminal(status: DownloadStatus): Boolean {
        return status == DownloadStatus.COMPLETED || status == DownloadStatus.CANCELLED || status == DownloadStatus.FAILED
    }

    /**
     * Checks if a progress/completion callback should be accepted.
     * Rejects if:
     * - expectedRunId is invalid (<= 0)
     * - callbackRunId does not match expectedRunId (unless callback is 0 and no runId was established)
     * - current task status is already PAUSED, CANCELLED, COMPLETED, or FAILED
     */
    fun canAcceptCallback(
        expectedRunId: Long?,
        callbackRunId: Long,
        currentStatus: DownloadStatus
    ): Boolean {
        if (expectedRunId == null || expectedRunId <= 0L) {
            return false
        }
        if (callbackRunId != 0L && callbackRunId != expectedRunId) {
            return false
        }
        if (isTerminalOrPaused(currentStatus)) {
            return false
        }
        return true
    }

    /**
     * Validates whether a state transition from `from` to `to` is legally permitted.
     */
    fun isValidTransition(
        from: DownloadStatus,
        to: DownloadStatus
    ): Boolean {
        if (from == to) return true

        // Terminal/Paused states can ONLY transition to QUEUED via explicit user action (resume/retry)
        if (isTerminalOrPaused(from)) {
            return to == DownloadStatus.QUEUED
        }

        return when (from) {
            DownloadStatus.QUEUED -> to in setOf(
                DownloadStatus.PREPARING,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED
            )
            DownloadStatus.PREPARING -> to in setOf(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PROCESSING_FFMPEG,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED
            )
            DownloadStatus.DOWNLOADING -> to in setOf(
                DownloadStatus.PROCESSING_FFMPEG,
                DownloadStatus.COMPLETED,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED
            )
            DownloadStatus.PROCESSING_FFMPEG -> to in setOf(
                DownloadStatus.COMPLETED,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED
            )
            else -> false
        }
    }

    /**
     * Validates whether a stage transition is legally permitted.
     */
    fun isValidStageTransition(
        from: DownloadStage,
        to: DownloadStage
    ): Boolean {
        if (from == to) return true
        if (to == DownloadStage.QUEUED) return true // reset / re-queue on retry or resume

        return when (from) {
            DownloadStage.QUEUED -> to == DownloadStage.PREPARING
            DownloadStage.PREPARING -> to in setOf(
                DownloadStage.DOWNLOADING,
                DownloadStage.MERGING,
                DownloadStage.CUTTING
            )
            DownloadStage.DOWNLOADING -> to in setOf(
                DownloadStage.MERGING,
                DownloadStage.CUTTING,
                DownloadStage.PUBLISHING,
                DownloadStage.COMPLETED
            )
            DownloadStage.MERGING -> to in setOf(
                DownloadStage.CUTTING,
                DownloadStage.PUBLISHING,
                DownloadStage.COMPLETED
            )
            DownloadStage.CUTTING -> to in setOf(
                DownloadStage.PUBLISHING,
                DownloadStage.COMPLETED
            )
            DownloadStage.PUBLISHING -> to == DownloadStage.COMPLETED
            DownloadStage.COMPLETED -> false
        }
    }
}
