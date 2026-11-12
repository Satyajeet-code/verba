package com.verba.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.verba.app.data.Backend
import com.verba.app.data.Counts
import com.verba.app.data.Format
import com.verba.app.data.Note
import com.verba.app.ui.glass.*
import com.verba.app.widget.NotesWidgetProvider

const val EXTRA_NOTE_ID = "note_id"

class MainActivity : GlassActivity() {
    private lateinit var list: ListView
    private lateinit var adapter: NoteAdapter
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var trashBadge: TextView
    private lateinit var emptyCard: View
    private lateinit var emptyText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var kind = "all"
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette
        val scene = buildScene()

        // ---- list with a big title header that scrolls away, iOS style
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER   // content is also drawn into the glass bars
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 4, 20, 10)
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p.accent)
            }
        }, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { rightMargin = dpi(8) })
        brand.addView(monoLabel("Notes", 12f, p.textDim, bold = true))
        header.addView(brand, header.linLp(WRAP, WRAP, b = 10))
        header.addView(dotLabel("Verba", 46f, p.text))
        subtitle = monoLabel("", 12f, p.textDim)
        header.addView(subtitle, header.linLp(WRAP, WRAP, t = 8, b = 16))
        header.addView(
            Segmented(this, p, listOf("All", "Text", "Voice"), 0) { i ->
                kind = listOf("all", "text", "voice")[i]
                reload()
            },
            header.linLp(MATCH, WRAP),
        )
        emptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(22, 26, 22, 26)
            addView(dotLabel("Empty", 26f, p.textFaint), linLp(WRAP, WRAP, b = 10))
            emptyText = label("", 15f, p.textDim, 400).apply { gravity = Gravity.CENTER }
            addView(emptyText)
            visibility = View.GONE
        }
        header.addView(emptyCard, header.linLp(MATCH, WRAP, t = 18))
        list.addHeaderView(header, null, false)
        adapter = NoteAdapter(p, ::open, ::moveToTrash)
        list.adapter = adapter
        scene.content.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- floating top bar: search + models + Recently Deleted
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 26f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(14, 4, 4, 4)
        }
        topBar.addView(iconView(Icon.SEARCH, p.textDim, 20), topBar.linLp(dpi(24), dpi(24), r = 8))
        val search = field(p, "Search", 15f).apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    handler.removeCallbacks(reloadRunnable)
                    handler.postDelayed(reloadRunnable, 180)
                }
            })
        }
        topBar.addView(search, LinearLayout.LayoutParams(0, dpi(44), 1f))
        topBar.addView(
            iconButton(p, Icon.SLIDERS, 44, GlassDrawable.Style.NONE, description = "Settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val trashWrap = FrameLayout(this)
        trashWrap.addView(
            iconButton(p, Icon.TRASH, 44, GlassDrawable.Style.NONE, description = "Recently Deleted") {
                startActivity(Intent(this, TrashActivity::class.java))
            },
        )
        trashBadge = monoLabel("", 9f, p.onAccent, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dpi(16)
            setPadding(dpi(4), 0, dpi(4), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8); setColor(p.accent)
            }
            visibility = View.GONE
        }
        trashWrap.addView(trashBadge, trashBadge.frameLp(WRAP, dpi(16), Gravity.TOP or Gravity.END, 0, 4, 4, 0))
        topBar.addView(trashWrap)
        scene.root.addView(topBar, topBar.frameLp(MATCH, WRAP, Gravity.TOP, 16, 8, 16, 0))

        // ---- floating bottom capsule: new text note / new voice note
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            glass(p, 32f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(8)
        }
        bottomBar.addView(
            pill(p, "Text", Icon.NOTE, heightDp = 48) { startActivity(Intent(this, EditorActivity::class.java)) },
            bottomBar.linLp(WRAP, WRAP, r = 8),
        )
        bottomBar.addView(
            pill(p, "Voice", Icon.MIC, GlassDrawable.Style.ACCENT, heightDp = 48) {
                startActivity(Intent(this, VoiceActivity::class.java))
            },
        )
        scene.root.addView(bottomBar, bottomBar.frameLp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        if (savedInstanceState == null) Backend.io.execute { runCatching { Backend.cleanupOrphans() } }

        val topEdge = frostedEdge(scene, top = true)
        val bottomEdge = frostedEdge(scene, top = false)
        edgeToEdge(scene.root) { l, t, r, b ->
            topEdge.setEdgeHeight(t + dpi(72))
            bottomEdge.setEdgeHeight(b + dpi(100))
            (topBar.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = t + dpi(8); leftMargin = l + dpi(16); rightMargin = r + dpi(16)
            }
            (bottomBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dpi(14)
            topBar.requestLayout(); bottomBar.requestLayout()
            list.setPadding(l, t + dpi(72), r, b + dpi(100))
        }
    }

    private val reloadRunnable = Runnable { reload() }

    /** Refresh the list when a voice note's text is ready (or starts being written). */
    private val transcriptions = object : com.verba.app.stt.AutoTranscriber.Listener {
        override fun onProgress(noteId: String, fraction: Float) {
            if (fraction == 0f) { handler.removeCallbacks(reloadRunnable); handler.postDelayed(reloadRunnable, 100) }
        }
        override fun onFinished(noteId: String, note: Note?, error: Throwable?) {
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 100)
        }
    }

    override fun onResume() {
        super.onResume()
        com.verba.app.stt.AutoTranscriber.addListener(transcriptions)
        com.verba.app.stt.AutoTranscriber.resumePending(this)
        reload()
    }

    override fun onPause() {
        super.onPause()
        com.verba.app.stt.AutoTranscriber.removeListener(transcriptions)
    }

    private fun reload() {
        val k = kind
        val q = query
        Backend.async({ Backend.listNotes(k, q) to Backend.counts() }) { r ->
            if (isDestroyed) return@async
            r.onFailure { GlassToast.show(this, "Could not load notes: ${it.message}") }
            r.onSuccess { (notes, counts) -> show(notes, counts) }
        }
    }

    private fun show(notes: List<Note>, c: Counts) {
        adapter.submit(notes)
        subtitle.setMonoText(when (c.all) {
            0 -> "No notes yet"
            1 -> "1 note"
            else -> "${c.all} notes" + if (c.voice > 0) " · ${c.voice} voice" else ""
        })
        trashBadge.text = if (c.trash > 99) "99+" else c.trash.toString()
        trashBadge.visibility = if (c.trash > 0) View.VISIBLE else View.GONE
        emptyCard.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = when {
            query.isNotBlank() -> "No notes match “$query”."
            kind == "voice" -> "No voice notes yet.\nTap Voice to record one."
            kind == "text" -> "No text notes yet.\nTap Text to type or speak one."
            else -> "Tap Text to type or speak a note in Hindi or English,\nor Voice to record audio."
        }
        topBar.invalidate()
        bottomBar.invalidate()
    }

    private fun open(n: Note) {
        val cls = if (n.isVoice) VoiceActivity::class.java else EditorActivity::class.java
        startActivity(Intent(this, cls).putExtra(EXTRA_NOTE_ID, n.id))
    }

    private fun moveToTrash(n: Note) {
        Backend.async({ Backend.trash(n.id) }) { r ->
            r.onFailure { GlassToast.show(this, "Could not delete: ${it.message}") }
            r.onSuccess {
                reload()
                NotesWidgetProvider.refresh(this)
                GlassToast.show(this, "Moved to Recently Deleted", "Undo") {
                    Backend.async({ Backend.restore(n.id) }) { reload(); NotesWidgetProvider.refresh(this) }
                }
            }
        }
    }
}

/** Glass cards for the note list. */
class NoteAdapter(
    private val p: Palette,
    private val onOpen: (Note) -> Unit,
    private val onDelete: (Note) -> Unit,
    private val trashMode: Boolean = false,
    private val onRestore: ((Note) -> Unit)? = null,
) : BaseAdapter() {
    private var items: List<Note> = emptyList()

    fun submit(notes: List<Note>) { items = notes; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    override fun hasStableIds() = true

    private class Holder(
        val card: LinearLayout,
        val icon: android.widget.ImageView,
        val iconWrap: FrameLayout,
        val title: TextView,
        val preview: TextView,
        val meta: TextView,
        val action: View,
        val restore: View?,
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val ctx = parent.context
        val view = convertView ?: build(parent)
        val h = view.tag as Holder
        val n = items[position]
        h.title.text = n.displayTitle
        val prev = if (n.isVoice && n.preview.isEmpty() && n.body.isEmpty()) "" else n.preview
        h.preview.text = prev
        h.preview.visibility = if (prev.isEmpty()) View.GONE else View.VISIBLE
        val parts = mutableListOf(
            if (trashMode && n.deletedAt != null) Format.deletedAgo(n.deletedAt) else Format.stamp(n.updatedAt)
        )
        if (n.isVoice) parts += Format.duration(n.durationMs)
        if (n.isVoice && com.verba.app.stt.AutoTranscriber.isRunning(n.id)) parts += "Transcribing…"
        Format.langChip(n.lang).takeIf { it.isNotEmpty() }?.let { parts += it }
        h.meta.setMonoText(parts.joinToString("  ·  "))
        h.icon.setImageDrawable(IconDrawable(if (n.isVoice) Icon.WAVE else Icon.NOTE,
            if (n.isVoice) p.onAccent else p.text, ctx.dpi(20), 1.7f, ctx.resources.displayMetrics.density))
        (h.iconWrap.background as GlassDrawable).style =
            if (n.isVoice) GlassDrawable.Style.ACCENT else GlassDrawable.Style.STRONG
        h.iconWrap.invalidate()
        h.card.setOnClickListener { onOpen(n) }
        h.action.setOnClickListener { onDelete(n) }
        h.restore?.setOnClickListener { onRestore?.invoke(n) }
        return view
    }

    private fun build(parent: ViewGroup): View {
        val ctx = parent.context
        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dpi(16), ctx.dpi(5), ctx.dpi(16), ctx.dpi(5)) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 22f, GlassDrawable.Style.CARD)
            padDp(14, 14, 6, 14)
            isClickable = true
            pressable()
        }
        val iconWrap = FrameLayout(ctx).apply { glass(p, 20f, GlassDrawable.Style.STRONG) }
        val icon = ctx.iconView(Icon.NOTE, p.text, 20)
        iconWrap.addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(iconWrap, card.linLp(ctx.dpi(40), ctx.dpi(40), r = 12))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = ctx.label("", 16f, p.text, 500, 1)
        val preview = ctx.label("", 14f, p.textDim, 400, 2).apply { setPadding(0, ctx.dpi(3), 0, 0) }
        val meta = ctx.monoLabel("", 10.5f, p.textFaint).apply { setPadding(0, ctx.dpi(8), 0, 0) }
        col.addView(title); col.addView(preview); col.addView(meta)
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        var restore: View? = null
        if (trashMode) {
            restore = ctx.iconButton(p, Icon.RESTORE, 40, GlassDrawable.Style.BUTTON, iconDp = 18, description = "Restore") {}
            card.addView(restore, card.linLp(ctx.dpi(40), ctx.dpi(40), l = 6, r = 6))
        }
        val action = ctx.iconButton(
            p, if (trashMode) Icon.CLOSE else Icon.TRASH, 40,
            if (trashMode) GlassDrawable.Style.BUTTON else GlassDrawable.Style.NONE,
            tint = if (trashMode) p.danger else p.textFaint, iconDp = 18,
            description = if (trashMode) "Delete permanently" else "Delete",
        ) {}
        card.addView(action, card.linLp(ctx.dpi(40), ctx.dpi(40), r = 4))
        wrap.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        wrap.tag = Holder(card, icon, iconWrap, title, preview, meta, action, restore)
        return wrap
    }
}
