package com.verba.app.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.verba.app.R
import com.verba.app.data.Backend
import com.verba.app.ui.glass.Accent
import com.verba.app.data.Format
import com.verba.app.data.Prefs
import com.verba.app.ui.EXTRA_AUTOSTART
import com.verba.app.ui.EXTRA_NOTE_ID
import com.verba.app.ui.EditorActivity
import com.verba.app.ui.MainActivity
import com.verba.app.ui.QuickNoteActivity
import com.verba.app.ui.QuickVoiceActivity
import com.verba.app.ui.VoiceActivity

/**
 * Resizable translucent-glass home-screen widget.
 *
 * Small sizes show two big buttons (text note / voice note). From about 3×2
 * cells it also lists recent notes; each row opens the note, and its trash
 * button moves the note to Recently Deleted.
 */
class NotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Backend.io.execute {
            try {
                update(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // Responsive layouts are chosen by the launcher; nothing to rebuild.
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
    }

    companion object {
        const val EXTRA_ACTION = "widget_action"
        const val EXTRA_KIND = "kind"
        const val ACTION_OPEN = "open"
        const val ACTION_DELETE = "delete"

        /** Re-draw every placed widget (after any note change). Safe from any thread. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, NotesWidgetProvider::class.java))
            if (ids.isEmpty()) return
            Backend.io.execute { runCatching { update(app, manager, ids) } }
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val notes = runCatching { Backend.widgetNotes(30) }.getOrDefault(emptyList())
            val total = runCatching { Backend.counts().all }.getOrDefault(notes.size)
            val colors = runCatching { Accent.current(context) }.getOrDefault(Accent.NOTHING_RED)
            val views = RemoteViews(
                mapOf(
                    SizeF(100f, 40f) to small(context, total).also { paint(it, colors) },
                    SizeF(180f, 160f) to large(context, notes, total).also { paint(it, colors) },
                )
            )
            for (id in ids) manager.updateAppWidget(id, views)
            Prefs.setWidgetAccentKey(context, accentKey(colors))
        }

        private fun accentKey(a: Accent.Colors) = "${a.accent}:${a.onAccent}:${a.mono}"

        /** Repaint the widget only if the accent colour changed since it was last drawn. */
        fun refreshIfStale(context: Context) {
            val app = context.applicationContext
            Backend.io.execute {
                val key = runCatching { accentKey(Accent.current(app)) }.getOrNull() ?: return@execute
                if (key != Prefs.widgetAccentKey(app)) refresh(app)
            }
        }

        private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

        /**
         * After a wallpaper or theme change. Android hands out the new theme
         * colour a moment after the wallpaper changes, so look again a few
         * times over the next seconds.
         */
        fun refreshSoon(context: Context) {
            val app = context.applicationContext
            for (delay in longArrayOf(0L, 1500L, 4000L, 9000L)) main.postDelayed({ refreshIfStale(app) }, delay)
        }

        /** Accent colour (follows the phone's theme, see [Accent]) on the glow and the voice button. */
        private fun paint(rv: RemoteViews, a: Accent.Colors) {
            rv.setInt(R.id.widget_glow, "setColorFilter", if (a.mono) 0xFFFFFFFF.toInt() else a.accent)
            rv.setInt(R.id.widget_glow, "setImageAlpha", if (a.mono) 110 else 255)
            rv.setInt(R.id.widget_voice_bg, "setColorFilter", a.accent)
            rv.setInt(R.id.widget_voice_icon, "setColorFilter", a.onAccent)
        }

        private fun countText(n: Int) = when (n) { 0 -> "No notes"; 1 -> "1 note"; else -> "$n notes" }

        private fun pi(context: Context, code: Int, intent: Intent, mutable: Boolean = false): PendingIntent =
            PendingIntent.getActivity(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
            )

        private fun quickText(c: Context) = pi(c, 1, Intent(c, QuickNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        private fun quickVoice(c: Context) = pi(c, 2, Intent(c, QuickVoiceActivity::class.java)
            .putExtra(EXTRA_AUTOSTART, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        private fun openApp(c: Context) = pi(c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        private fun small(c: Context, total: Int): RemoteViews =
            RemoteViews(c.packageName, R.layout.widget_notes_small).apply {
                setTextViewText(R.id.widget_count, countText(total))
                setOnClickPendingIntent(R.id.widget_add_text, quickText(c))
                setOnClickPendingIntent(R.id.widget_add_voice, quickVoice(c))
                setOnClickPendingIntent(R.id.widget_title, openApp(c))
            }

        private fun large(c: Context, notes: List<Backend.WidgetNote>, total: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_notes)
            rv.setTextViewText(R.id.widget_count, countText(total))
            rv.setOnClickPendingIntent(R.id.widget_title, openApp(c))
            rv.setOnClickPendingIntent(R.id.widget_add_text, quickText(c))
            rv.setOnClickPendingIntent(R.id.widget_add_voice, quickVoice(c))

            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            for (n in notes) {
                val item = RemoteViews(c.packageName, R.layout.widget_note_item)
                item.setTextViewText(R.id.item_title, n.title)
                // Mono caps label: time and length only (note text stays in the normal font above).
                val meta = buildString {
                    append(Format.stamp(n.updatedAt))
                    append("  ·  ").append(if (n.kind == "voice") "Voice " + Format.duration(n.durationMs) else "Text")
                    if (n.kind == "voice" && com.verba.app.stt.AutoTranscriber.isRunning(n.id)) append("  ·  Transcribing…")
                }
                item.setTextViewText(R.id.item_meta, meta)
                item.setImageViewResource(R.id.item_icon, if (n.kind == "voice") R.drawable.ic_w_voice else R.drawable.ic_w_text)
                item.setOnClickFillInIntent(
                    R.id.item_root,
                    Intent().putExtra(EXTRA_ACTION, ACTION_OPEN).putExtra(EXTRA_NOTE_ID, n.id).putExtra(EXTRA_KIND, n.kind),
                )
                item.setOnClickFillInIntent(
                    R.id.item_delete,
                    Intent().putExtra(EXTRA_ACTION, ACTION_DELETE).putExtra(EXTRA_NOTE_ID, n.id).putExtra(EXTRA_KIND, n.kind),
                )
                items.addItem(stableId(n.id), item)
            }
            rv.setRemoteAdapter(R.id.widget_list, items.build())
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)
            rv.setPendingIntentTemplate(
                R.id.widget_list,
                pi(c, 4, Intent(c, WidgetRouterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), mutable = true),
            )
            rv.setViewVisibility(R.id.widget_empty, if (notes.isEmpty()) View.VISIBLE else View.GONE)
            return rv
        }

        private fun stableId(id: String): Long =
            id.take(15).toLongOrNull(16) ?: id.hashCode().toLong()
    }
}

/**
 * Invisible activity behind the widget's list rows: opens a note, or moves it
 * to Recently Deleted and closes again.
 */
class WidgetRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_NOTE_ID)
        val kind = intent.getStringExtra(NotesWidgetProvider.EXTRA_KIND)
        when {
            id == null -> finish()
            intent.getStringExtra(NotesWidgetProvider.EXTRA_ACTION) == NotesWidgetProvider.ACTION_DELETE -> {
                val app = applicationContext
                Backend.async({ Backend.trash(id) }) { r ->
                    Toast.makeText(app, if (r.isSuccess) "Moved to Recently Deleted" else "Could not delete", Toast.LENGTH_SHORT).show()
                    NotesWidgetProvider.refresh(app)
                    finish()
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                }
            }
            else -> {
                val cls = if (kind == "voice") VoiceActivity::class.java else EditorActivity::class.java
                startActivity(Intent(this, cls).putExtra(EXTRA_NOTE_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
        }
    }
}
