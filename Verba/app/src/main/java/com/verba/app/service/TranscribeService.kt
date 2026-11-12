package com.verba.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.verba.app.VerbaApp
import com.verba.app.stt.AutoTranscriber
import com.verba.app.ui.MainActivity

/**
 * Keeps the app alive while voice notes are turned into text, so closing the
 * recorder (or the widget's pop-up) right away cannot cut a transcription off.
 * Stops itself as soon as the last one is done.
 */
class TranscribeService : Service() {
    private var jobs = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra(EXTRA_ID)
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (id == null || path == null) {
            if (jobs == 0) stopNow()
            return START_NOT_STICKY
        }
        if (jobs == 0) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "verba:transcribe")
                .apply { acquire(30 * 60 * 1000L) }
        }
        jobs++
        AutoTranscriber.execute(applicationContext, id, path, intent.getBooleanExtra(EXTRA_REPLACE, false)) {
            jobs--
            if (jobs <= 0) stopNow()
        }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        jobs = 0
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, VerbaApp.CHANNEL_TRANSCRIBE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Transcribing voice note")
            .setContentText("The text will appear in the note and the widget")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 2101
        private const val EXTRA_ID = "note_id"
        private const val EXTRA_PATH = "audio_path"
        private const val EXTRA_REPLACE = "replace"

        /** False if the system refused to start a foreground service right now. */
        fun start(ctx: Context, noteId: String, audioPath: String, replace: Boolean): Boolean = try {
            ctx.startForegroundService(
                Intent(ctx, TranscribeService::class.java)
                    .putExtra(EXTRA_ID, noteId)
                    .putExtra(EXTRA_PATH, audioPath)
                    .putExtra(EXTRA_REPLACE, replace),
            )
            true
        } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
