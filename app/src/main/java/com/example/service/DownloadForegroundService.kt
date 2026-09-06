package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.example.MainActivity
import com.example.R
import com.example.data.settings.AppSettings
import com.example.domain.model.DownloadStatus
import com.example.downloader.DownloadManager

class DownloadForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DownloadVideos:ForegroundServiceWakeLock")
        } catch (_: Exception) {}
        createNotificationChannels()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(60 * 60 * 1000L) // 1 hour maximum safety timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        val appSettings = AppSettings.getInstance(this)
        val userWantsNotifications = appSettings.showNotifications.value

        when (action) {
            ACTION_CANCEL -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadManager.getInstance(applicationContext).cancelDownload(taskId)
                }
                stopForegroundIfIdle()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadManager.getInstance(applicationContext).pauseDownload(taskId)
                }
                stopForegroundIfIdle()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadManager.getInstance(applicationContext).resumeDownload(taskId)
                }
                return START_NOT_STICKY
            }
            ACTION_RETRY -> {
                if (!taskId.isNullOrBlank()) {
                    DownloadManager.getInstance(applicationContext).retryDownload(taskId)
                }
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val statusName = intent?.getStringExtra(EXTRA_STATUS) ?: DownloadStatus.DOWNLOADING.name
        val status = try { DownloadStatus.valueOf(statusName) } catch (_: Exception) { DownloadStatus.DOWNLOADING }
        val speed = intent?.getStringExtra(EXTRA_SPEED) ?: ""
        val contentUriStr = intent?.getStringExtra(EXTRA_CONTENT_URI)
        val isFinished = intent?.getBooleanExtra(EXTRA_FINISHED, false) ?: false
        val isFailed = intent?.getBooleanExtra(EXTRA_FAILED, false) ?: false
        val errorMessage = intent?.getStringExtra(EXTRA_ERROR_MESSAGE)

        if (isFinished) {
            if (userWantsNotifications) {
                val completedNotification = buildCompletedNotification(title, contentUriStr)
                notificationManager.notify(NOTIFICATION_ID_COMPLETED_BASE + (taskId?.hashCode() ?: 0), completedNotification)
            }
            if (!isForegroundActive) {
                val dummyNotif = buildProgressNotification(title, 100, taskId, DownloadStatus.COMPLETED, "")
                startForegroundWithNotification(dummyNotif)
            }
            stopForegroundIfIdle()
        } else if (isFailed) {
            if (userWantsNotifications) {
                val failedNotification = buildFailedNotification(title, taskId, errorMessage)
                notificationManager.notify(NOTIFICATION_ID_FAILED_BASE + (taskId?.hashCode() ?: 0), failedNotification)
            }
            if (!isForegroundActive) {
                val dummyNotif = buildProgressNotification(title, 0, taskId, DownloadStatus.FAILED, "")
                startForegroundWithNotification(dummyNotif)
            }
            stopForegroundIfIdle()
        } else {
            // Android OS requires foreground services to display a notification.
            // Even if user disabled alert notifications, we must keep a valid ongoing notification to satisfy the OS.
            val notification = buildProgressNotification(title, progress, taskId, status, speed)
            startForegroundWithNotification(notification)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
            acquireWakeLock()
        } catch (_: Exception) {}
    }

    private fun stopForegroundIfIdle() {
        if (!DownloadManager.getInstance(applicationContext).hasActiveDownloads()) {
            releaseWakeLock()
            if (isForegroundActive) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                isForegroundActive = false
            }
            stopSelf()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active video download progress"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_ID_COMPLETED,
                "Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when video downloads finish"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    private fun buildProgressNotification(
        title: String,
        progress: Int,
        taskId: String?,
        status: DownloadStatus,
        speed: String
    ): android.app.Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause Action Intent
        val pauseIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel Action Intent
        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (status) {
            DownloadStatus.PROCESSING_FFMPEG -> getString(R.string.status_processing_ffmpeg)
            DownloadStatus.PREPARING -> getString(R.string.status_preparing)
            DownloadStatus.PAUSED -> getString(R.string.status_paused)
            else -> {
                val speedPart = if (speed.isNotBlank()) " • $speed" else ""
                "${getString(R.string.status_downloading)} $progress%$speedPart"
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setProgress(100, progress, progress == 0 && status != DownloadStatus.PAUSED)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PROCESSING_FFMPEG || status == DownloadStatus.PREPARING) {
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_pause), pausePendingIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_cancel), cancelPendingIntent)

        return builder.build()
    }

    private fun buildCompletedNotification(title: String, contentUriStr: String?): android.app.Notification {
        val openIntent = if (!contentUriStr.isNullOrBlank()) {
            val uri = contentUriStr.toUri()
            val mimeType = com.example.storage.MediaStoreHelper.resolveMimeType(this, uri, null)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_completed))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, getString(R.string.btn_open), pendingIntent)
            .build()
    }

    private fun buildFailedNotification(title: String, taskId: String?, errorMessage: String?): android.app.Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 20, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_RETRY
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val retryPendingIntent = PendingIntent.getService(
            this, 21, retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(errorMessage ?: getString(R.string.status_failed))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_rotate, getString(R.string.btn_retry), retryPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "download_videos_channel"
        const val CHANNEL_ID_COMPLETED = "download_completed_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_COMPLETED_BASE = 2000
        const val NOTIFICATION_ID_FAILED_BASE = 3000

        const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
        const val ACTION_RETRY = "com.example.service.ACTION_RETRY"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_CONTENT_URI = "extra_content_uri"
        const val EXTRA_FINISHED = "extra_finished"
        const val EXTRA_FAILED = "extra_failed"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        fun startOrUpdate(
            context: Context,
            taskId: String,
            title: String,
            progress: Int,
            status: DownloadStatus,
            speed: String = ""
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_STATUS, status.name)
                putExtra(EXTRA_SPEED, speed)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Guard against ForegroundServiceStartNotAllowedException on Android 12+
                e.printStackTrace()
            }
        }

        fun onTaskCompleted(context: Context, taskId: String, title: String, contentUri: String?) {
            val appSettings = AppSettings.getInstance(context)
            if (appSettings.showNotifications.value) {
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val completedNotification = buildStaticCompletedNotification(context, title, contentUri)
                    nm.notify(NOTIFICATION_ID_COMPLETED_BASE + taskId.hashCode(), completedNotification)
                } catch (_: Throwable) {}
            }

            // If there are still active downloads, update service state; otherwise stop if running
            try {
                val intent = Intent(context, DownloadForegroundService::class.java).apply {
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CONTENT_URI, contentUri)
                    putExtra(EXTRA_FINISHED, true)
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun onTaskFailed(context: Context, taskId: String, title: String, errorMessage: String) {
            val appSettings = AppSettings.getInstance(context)
            if (appSettings.showNotifications.value) {
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val failedNotification = buildStaticFailedNotification(context, title, taskId, errorMessage)
                    nm.notify(NOTIFICATION_ID_FAILED_BASE + taskId.hashCode(), failedNotification)
                } catch (_: Throwable) {}
            }

            try {
                val intent = Intent(context, DownloadForegroundService::class.java).apply {
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                    putExtra(EXTRA_FAILED, true)
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun updateOrDismissIfIdle(
            context: Context,
            taskId: String,
            title: String,
            status: DownloadStatus,
            progress: Int,
            speed: String
        ) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java).apply {
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_STATUS, status.name)
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_SPEED, speed)
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        private fun buildStaticCompletedNotification(context: Context, title: String, contentUriStr: String?): android.app.Notification {
            val openIntent = if (!contentUriStr.isNullOrBlank()) {
                val uri = contentUriStr.toUri()
                val mimeType = com.example.storage.MediaStoreHelper.resolveMimeType(context, uri, null)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(context, MainActivity::class.java)
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID_COMPLETED)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_completed))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_media_play, context.getString(R.string.btn_open), pendingIntent)
                .build()
        }

        private fun buildStaticFailedNotification(context: Context, title: String, taskId: String?, errorMessage: String?): android.app.Notification {
            val contentIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 20, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val retryIntent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_RETRY
                putExtra(EXTRA_TASK_ID, taskId)
            }
            val retryPendingIntent = PendingIntent.getService(
                context, 21, retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID_COMPLETED)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(errorMessage ?: context.getString(R.string.status_failed))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_rotate, context.getString(R.string.btn_retry), retryPendingIntent)
                .build()
        }
    }
}
