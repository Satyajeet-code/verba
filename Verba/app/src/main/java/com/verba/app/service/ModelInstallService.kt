package com.verba.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.verba.app.VerbaApp
import com.verba.app.data.Backend
import com.verba.app.data.Format
import com.verba.app.data.ProgressSink
import com.verba.app.ui.SettingsActivity
import java.util.concurrent.CopyOnWriteArrayList

/** Live state of the (single) model download, observed by the Models screen. */
object InstallState {
    data class Progress(
        val modelId: String,
        val phase: String,          // starting, download, verify, extract, extras, done, error, cancelled
        val done: Long = 0,
        val total: Long = 0,
        val error: String? = null,
    ) {
        val running get() = phase !in setOf("done", "error", "cancelled")
        val fraction get() = if (total > 0) done.toFloat() / total else 0f
    }

    @Volatile var current: Progress? = null
        private set
    private val listeners = CopyOnWriteArrayList<(Progress) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Replays the current state only while a download is running. */
    fun observe(l: (Progress) -> Unit) { listeners += l; current?.takeIf { it.running }?.let(l) }
    fun remove(l: (Progress) -> Unit) { listeners -= l }

    fun post(p: Progress) {
        current = p
        main.post { listeners.forEach { it(p) } }
    }
}

/** Python calls these two methods while installing. */
class InstallSink(private val modelId: String, private val service: ModelInstallService) : ProgressSink {
    override fun onProgress(phase: String, done: Long, total: Long) {
        InstallState.post(InstallState.Progress(modelId, phase, done, total))
        service.showProgress(modelId, phase, done, total)
    }

    override fun isCancelled(): Boolean = ModelInstallService.cancelRequested
}

/**
 * Foreground service so a ~0.5–0.9 GB download keeps going with the screen off
 * or the app in the background. The partial file is kept, so a cancelled or
 * failed download resumes next time.
 */
class ModelInstallService : Service() {
    private var worker: Thread? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_MODEL)
        startForeground(NOTIFICATION_ID, notification("Preparing download…", 0, 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (id == null || worker?.isAlive == true) return START_NOT_STICKY

        cancelRequested = false
        InstallState.post(InstallState.Progress(id, "starting"))
        worker = Thread({
            val wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "verba:model-download")
            wl.acquire(3 * 60 * 60 * 1000L)
            val result = try {
                Backend.installModel(id, InstallSink(id, this))
            } catch (t: Throwable) {
                t.message ?: t.toString()
            } finally {
                if (wl.isHeld) wl.release()
            }
            val p = when (result) {
                "" -> InstallState.Progress(id, "done", 1, 1)
                "cancelled" -> InstallState.Progress(id, "cancelled")
                else -> InstallState.Progress(id, "error", error = result)
            }
            InstallState.post(p)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (p.phase != "cancelled") {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID + 1,
                    notification(if (p.phase == "done") "Speech model ready" else "Download failed: ${p.error}", 0, 0, false, ongoing = false),
                )
            }
            stopSelf()
        }, "verba-install").apply { start() }
        return START_NOT_STICKY
    }

    fun showProgress(modelId: String, phase: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 800) return
        lastNotify = now
        val text = when (phase) {
            "download" -> "Downloading ${Format.megabytes(done)} of ${Format.megabytes(total)}"
            "verify" -> "Checking the download…"
            "extract" -> "Unpacking…"
            "extras" -> "Finishing…"
            else -> "Preparing…"
        }
        val max = if (total > 0) 1000 else 0
        val cur = if (total > 0) (done * 1000 / total).toInt() else 0
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, max, cur, total <= 0))
    }

    private fun notification(text: String, max: Int, progress: Int, indeterminate: Boolean, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, VerbaApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Verba · speech model")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            b.setProgress(max, progress, indeterminate)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, ModelInstallService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "Pause", cancel).build())
        } else {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
        }
        return b.build()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_MODEL = "model"
        private const val NOTIFICATION_ID = 7001

        @Volatile var cancelRequested = false

        fun start(ctx: Context, modelId: String) {
            ctx.startForegroundService(
                Intent(ctx, ModelInstallService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODEL, modelId)
            )
        }

        fun cancel() { cancelRequested = true }
    }
}
