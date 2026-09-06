package com.example.data.local

import androidx.room.TypeConverter
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toStatus(value: String?): DownloadStatus {
        return try {
            if (value != null) DownloadStatus.valueOf(value) else DownloadStatus.QUEUED
        } catch (_: Exception) {
            DownloadStatus.QUEUED
        }
    }

    @TypeConverter
    fun fromStage(stage: DownloadStage?): String? {
        return stage?.name
    }

    @TypeConverter
    fun toStage(value: String?): DownloadStage {
        return try {
            if (value != null) DownloadStage.valueOf(value) else DownloadStage.QUEUED
        } catch (_: Exception) {
            DownloadStage.QUEUED
        }
    }
}
