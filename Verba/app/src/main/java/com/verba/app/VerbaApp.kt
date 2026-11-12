package com.verba.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import com.verba.app.data.Backend
import com.verba.app.stt.SttManager
import com.verba.app.widget.NotesWidgetProvider

class VerbaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.verba.app.ui.glass.Fonts.init(this)
        Backend.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a speech model downloads"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSCRIBE, "Transcribing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a voice note is turned into text"
            }
        )
        // Accent colour follows the wallpaper / phone theme: repaint the widget when it changes,
        // also when Verba is not running (ThemeWatchJob), and check once now.
        com.verba.app.service.ThemeWatchJob.schedule(this)
        NotesWidgetProvider.refreshIfStale(this)
        // Accent colour follows the wallpaper / phone theme: repaint the widget when it changes.
        runCatching {
            getSystemService(android.app.WallpaperManager::class.java)?.addOnColorsChangedListener(
                { _, which ->
                    if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) NotesWidgetProvider.refreshSoon(this)
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A new theme colour arrives as a configuration change.
        NotesWidgetProvider.refreshSoon(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The speech model holds 1–1.6 GB; give it back once we are in the background.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) SttManager.release()
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_TRANSCRIBE = "transcribe"
    }
}
