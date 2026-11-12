package com.verba.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.verba.app.data.Backend
import com.verba.app.data.Note
import com.verba.app.ui.glass.*
import com.verba.app.widget.NotesWidgetProvider

/**
 * Recently Deleted. Notes deleted anywhere (app or widget) wait here until you
 * restore them or delete them permanently — nothing expires on its own.
 */
class TrashActivity : GlassActivity() {
    private lateinit var adapter: NoteAdapter
    private lateinit var subtitle: TextView
    private lateinit var emptyCard: View
    private lateinit var deleteAll: LinearLayout
    private lateinit var top: LinearLayout
    private var items: List<Note> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette
        val scene = buildScene()
        val list = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(20, 4, 20, 10) }
        header.addView(dotLabel("Recently deleted", 32f, p.text))
        subtitle = monoLabel("", 11.5f, p.textDim, lines = 2)
        header.addView(subtitle, header.linLp(MATCH, WRAP, t = 4))
        emptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(22, 26, 22, 26)
            addView(dotLabel("Empty", 26f, p.textFaint), linLp(WRAP, WRAP, b = 10))
            addView(label("Deleted notes wait here until you remove them.", 15f, p.textDim, 400).apply { gravity = Gravity.CENTER })
            visibility = View.GONE
        }
        header.addView(emptyCard, header.linLp(MATCH, WRAP, t = 18))
        list.addHeaderView(header, null, false)
        adapter = NoteAdapter(p, ::preview, ::deleteForever, trashMode = true, onRestore = ::restore)
        list.adapter = adapter
        scene.content.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))

        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 26f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(4)
        }
        top.addView(iconButton(p, Icon.BACK, 44, GlassDrawable.Style.NONE, description = "Back") { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        deleteAll = pill(p, "Delete all", Icon.TRASH, GlassDrawable.Style.DANGER, heightDp = 40) { confirmEmpty() }
        top.addView(deleteAll)
        scene.root.addView(top, top.frameLp(MATCH, WRAP, Gravity.TOP, 14, 8, 14, 0))

        val topEdge = frostedEdge(scene, top = true)
        val bottomEdge = frostedEdge(scene, top = false)
        edgeToEdge(scene.root) { l, t, r, b ->
            topEdge.setEdgeHeight(t + dpi(70))
            bottomEdge.setEdgeHeight(b + dpi(20))
            (top.layoutParams as FrameLayout.LayoutParams).apply { topMargin = t + dpi(8); leftMargin = l + dpi(14); rightMargin = r + dpi(14) }
            top.requestLayout()
            list.setPadding(l, t + dpi(70), r, b + dpi(24))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Backend.async({ Backend.listTrash() }) { r ->
            if (isDestroyed) return@async
            r.onFailure { GlassToast.show(this, "Could not load: ${it.message}", bottomDp = 40) }
            r.onSuccess { notes ->
                items = notes
                adapter.submit(notes)
                subtitle.setMonoText(if (notes.isEmpty()) "Nothing is removed automatically"
                else "${notes.size} note${if (notes.size == 1) "" else "s"} · kept until you delete them")
                emptyCard.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                deleteAll.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
                top.invalidate()
            }
        }
    }

    private fun preview(n: Note) {
        GlassDialog.confirm(
            this, n.displayTitle,
            (if (n.isVoice) "Voice note. " else "") + n.body.take(400).ifBlank { "(empty)" },
            "Restore", cancelLabel = "Close", plainTitle = true,
        ) { restore(n) }
    }

    private fun restore(n: Note) {
        Backend.async({ Backend.restore(n.id) }) { r ->
            r.onSuccess {
                NotesWidgetProvider.refresh(this)
                GlassToast.show(this, "Restored", "Open", bottomDp = 40) {
                    val cls = if (n.isVoice) VoiceActivity::class.java else EditorActivity::class.java
                    startActivity(Intent(this, cls).putExtra(EXTRA_NOTE_ID, n.id))
                }
                reload()
            }
        }
    }

    private fun deleteForever(n: Note) {
        GlassDialog.confirm(
            this, "Delete permanently?",
            "“${n.displayTitle}” will be deleted forever" + if (n.isVoice) ", including its recording." else ".",
            "Delete", destructive = true,
        ) {
            Backend.async({ Backend.deleteForever(n.id) }) { r ->
                r.onFailure { GlassToast.show(this, "Could not delete: ${it.message}", bottomDp = 40) }
                reload()
            }
        }
    }

    private fun confirmEmpty() {
        if (items.isEmpty()) return
        GlassDialog.confirm(
            this, "Delete all ${items.size}?",
            "Everything in Recently Deleted will be deleted forever. This cannot be undone.",
            "Delete all", destructive = true,
        ) {
            Backend.async({ Backend.emptyTrash() }) { r ->
                r.onSuccess { GlassToast.show(this, "Deleted $it note${if (it == 1) "" else "s"}", bottomDp = 40) }
                reload()
            }
        }
    }
}
