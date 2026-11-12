package com.verba.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.verba.app.widget.NotesWidgetProvider

/**
 * Wakes Verba when the phone's theme colours change, even if the app is not
 * running, so the home-screen widget can repaint itself in the new colour.
 *
 * Android keeps the wallpaper-based theme in a system setting; the job runs
 * whenever that setting changes. (Content-triggered jobs end after each run
 * and do not survive a reboot, so the job is scheduled again every time and
 * whenever the app starts.)
 */
class ThemeWatchJob : JobService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        // The new colours reach apps a moment after the setting changes: look a few times.
        for (delay in longArrayOf(0L, 1500L, 4000L)) main.postDelayed({ NotesWidgetProvider.refreshIfStale(this) }, delay)
        main.postDelayed({
            schedule(this, replace = true)   // watch for the next change
            jobFinished(params, false)
        }, 9000L)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        main.removeCallbacksAndMessages(null)
        return false
    }

    companion object {
        private const val JOB_ID = 4711
        private const val THEME_SETTING = "theme_customization_overlay_packages"

        /** [replace] = false keeps a job that is already waiting (or running). */
        fun schedule(ctx: Context, replace: Boolean = false) {
            runCatching {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (!replace && js.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, ThemeWatchJob::class.java))
                    .addTriggerContentUri(JobInfo.TriggerContentUri(Settings.Secure.getUriFor(THEME_SETTING), 0))
                    .setTriggerContentUpdateDelay(500)
                    .setTriggerContentMaxDelay(3000)
                    .build()
                js.schedule(job)
            }
        }
    }
}
