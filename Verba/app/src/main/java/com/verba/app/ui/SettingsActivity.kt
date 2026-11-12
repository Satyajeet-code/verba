package com.verba.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.verba.app.data.Backend
import com.verba.app.data.Format
import com.verba.app.data.GeminiModels
import com.verba.app.data.ModelInfo
import com.verba.app.data.Prefs
import com.verba.app.data.Secrets
import com.verba.app.stt.GeminiClient
import com.verba.app.service.InstallState
import com.verba.app.service.ModelInstallService
import com.verba.app.stt.SttManager
import com.verba.app.ui.glass.*

/** Settings: auto-transcribe, the language you speak, and the on-device speech models. */
class SettingsActivity : GlassActivity() {
    private lateinit var cards: LinearLayout
    private lateinit var free: TextView
    private lateinit var cloudCard: LinearLayout
    private var keyDraft = ""
    private val views = mutableMapOf<String, CardViews>()
    private var models: List<ModelInfo> = emptyList()
    private val showingPause = mutableSetOf<String>()
    private val observer: (InstallState.Progress) -> Unit = { onProgress(it) }

    private class CardViews(
        val badge: TextView,
        val progress: GlassProgress,
        val progressLabel: TextView,
        val buttons: LinearLayout,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette
        val scene = buildScene()
        val scroll = ScrollView(this).apply { clipToPadding = false; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(16, 0, 16, 0) }
        scroll.addView(col)
        scene.content.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        col.addView(dotLabel("Settings", 46f, p.text), col.linLp(WRAP, WRAP, l = 4, b = 18))

        // ---- voice notes
        val voice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(18, 16, 18, 18)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(monoLabel("Auto-transcribe", 12f, p.text, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(DotSwitch(this, p, Prefs.autoTranscribe(this)) { on ->
            Prefs.setAutoTranscribe(this, on)
            GlassToast.show(this, if (on) "New voice notes will be transcribed automatically" else "Auto-transcribe off", bottomDp = 40)
        })
        voice.addView(row)
        voice.addView(
            label("Every new voice recording is turned into text on your phone as soon as you save it. " +
                "Needs a downloaded speech model.", 13f, p.textDim, 400),
            voice.linLp(MATCH, WRAP, t = 8),
        )
        col.addView(voice, col.linLp(MATCH, WRAP, b = 12))

        // ---- language
        val lang = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(18, 16, 18, 18)
        }
        lang.addView(monoLabel("Language you speak", 12f, p.text, bold = true))
        lang.addView(label("Auto detects Hindi or English. Pick one if Auto guesses wrong. " +
            "Srota Hinglish models ignore this: they handle both languages in the same sentence.", 13f, p.textDim, 400),
            lang.linLp(MATCH, WRAP, t = 4, b = 12))
        val codes = listOf("auto", "hi", "en")
        lang.addView(
            Segmented(this, p, listOf("Auto", "हिन्दी", "English"), codes.indexOf(Prefs.language(this)).coerceAtLeast(0)) {
                Prefs.setLanguage(this, codes[it])
            },
            lang.linLp(MATCH, WRAP),
        )
        col.addView(lang, col.linLp(MATCH, WRAP, b = 12))

        // ---- colour
        val colour = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(18, 16, 18, 18)
        }
        colour.addView(monoLabel("Colour", 12f, p.text, bold = true))
        colour.addView(
            label("Black always stays the base. Auto takes your phone's theme colour (it comes from your wallpaper) " +
                "and makes it as vivid as Nothing red; a black-and-white wallpaper makes Verba black and white. " +
                "Red is the original black and red; Mono is always black and white.", 13f, p.textDim, 400),
            colour.linLp(MATCH, WRAP, t = 4, b = 12),
        )
        val modes = listOf(Accent.AUTO, Accent.RED, Accent.MONO)
        colour.addView(Segmented(this, p, listOf("Auto", "Red", "Mono"), modes.indexOf(Prefs.accentMode(this)).coerceAtLeast(0)) { i ->
            if (Prefs.accentMode(this) != modes[i]) {
                Prefs.setAccentMode(this, modes[i])
                com.verba.app.widget.NotesWidgetProvider.refresh(this)
                recreate()
            }
        }, colour.linLp(MATCH, WRAP))
        val auto = Accent.auto(this)
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        swatchRow.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(auto.accent)
                setStroke(dpi(1), 0x33FFFFFF)
            }
        }, LinearLayout.LayoutParams(dpi(18), dpi(18)))
        swatchRow.addView(label("Auto right now: ${auto.source}", 12.5f, p.textDim, 400),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(10) })
        colour.addView(swatchRow, colour.linLp(MATCH, WRAP, t = 12))
        col.addView(colour, col.linLp(MATCH, WRAP, b = 12))

        col.addView(monoLabel("Speech models", 11.5f, p.textDim, bold = true), col.linLp(WRAP, WRAP, l = 6, t = 10, b = 6))
        col.addView(
            label("Speech-to-text runs on your phone, offline. Download a model once; only one is loaded at a time.",
                13f, p.textDim, 400),
            col.linLp(MATCH, WRAP, l = 6, r = 6, b = 12),
        )
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(cards)
        free = monoLabel("", 10.5f, p.textFaint).apply { gravity = Gravity.CENTER }
        col.addView(free, col.linLp(MATCH, WRAP, t = 6, b = 6))
        col.addView(
            label("Models come from the sherpa-onnx GitHub releases page or this app's own Releases page, " +
                "and are checked with SHA-256 after download.",
                12f, p.textFaint, 400).apply { gravity = Gravity.CENTER },
            col.linLp(MATCH, WRAP, l = 12, r = 12, b = 8),
        )

        // ---- cloud: Gemini with your own key
        col.addView(monoLabel("Cloud · your own key", 11.5f, p.textDim, bold = true), col.linLp(WRAP, WRAP, l = 6, t = 18, b = 6))
        col.addView(
            label("Optional. Use Google's Gemini with your own API key instead of an on-device model. " +
                "Your recordings are then sent to Google to be transcribed, and need internet.", 13f, p.textDim, 400),
            col.linLp(MATCH, WRAP, l = 6, r = 6, b = 12),
        )
        cloudCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 28f, GlassDrawable.Style.CARD)
            padDp(20, 18, 20, 18)
        }
        col.addView(cloudCard, col.linLp(MATCH, WRAP, b = 12))
        renderCloud()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 26f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(4)
        }
        top.addView(iconButton(p, Icon.BACK, 44, GlassDrawable.Style.NONE, description = "Back") { finish() })
        top.addView(monoLabel("Settings", 11.5f, p.textDim, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dpi(44), 1))
        scene.root.addView(top, top.frameLp(MATCH, WRAP, Gravity.TOP, 14, 8, 14, 0))

        val topEdge = frostedEdge(scene, top = true)
        val bottomEdge = frostedEdge(scene, top = false)
        edgeToEdge(scene.root) { l, t, r, b ->
            topEdge.setEdgeHeight(t + dpi(72))
            bottomEdge.setEdgeHeight(b + dpi(20))
            (top.layoutParams as FrameLayout.LayoutParams).apply { topMargin = t + dpi(8); leftMargin = l + dpi(14); rightMargin = r + dpi(14) }
            top.requestLayout()
            scroll.setPadding(l, t + dpi(72), r, b + dpi(24))
        }
    }

    override fun onResume() {
        super.onResume()
        InstallState.observe(observer)
        reload()
    }

    override fun onPause() {
        super.onPause()
        InstallState.remove(observer)
    }

    private fun reload() {
        Backend.async({ Backend.modelStatus() }) { r ->
            if (isDestroyed) return@async
            r.onFailure { GlassToast.show(this, "Could not read models: ${it.message}", bottomDp = 40) }
            r.onSuccess { (list, freeBytes) ->
                models = list
                free.setMonoText("Free storage ${Format.megabytes(freeBytes)}")
                render()
            }
        }
    }

    private fun render() {
        val p = palette
        cards.removeAllViews()
        views.clear()
        showingPause.clear()
        val geminiActive = Prefs.activeModel(this) == Prefs.GEMINI && Secrets.hasGeminiKey(this)
        val active = if (geminiActive) null else Prefs.activeModel(this)?.takeIf { id -> models.any { it.id == id && it.installed } }
            ?: models.firstOrNull { it.installed }?.id
        for (m in models) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                glass(p, 28f, GlassDrawable.Style.CARD)
                padDp(20, 18, 20, 18)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(m.name, 20f, p.text, 700))
            names.addView(monoLabel(m.vendor + if (m.mode == "live") " · live" else " · after each pause", 10.5f, p.textDim), names.linLp(WRAP, WRAP, t = 2))
            head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
            val badge = monoLabel("", 10f, p.onAccent, bold = true).apply {
                setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12); setColor(p.accent) }
            }
            head.addView(badge)
            card.addView(head)
            card.addView(label(m.tagline, 15f, p.text, 500), card.linLp(MATCH, WRAP, t = 12))
            card.addView(label(m.detail, 13f, p.textDim, 400).apply { setLineSpacing(0f, 1.15f) }, card.linLp(MATCH, WRAP, t = 4))
            card.addView(
                monoLabel("${Format.megabytes(m.downloadBytes)}  ·  RAM ${"%.1f".format(m.ramMb / 1000f)} GB  ·  ${m.license}", 10.5f, p.textFaint, lines = 2),
                card.linLp(MATCH, WRAP, t = 8),
            )
            val prog = GlassProgress(this, p).apply { visibility = View.GONE }
            card.addView(prog, card.linLp(MATCH, dpi(6), t = 14))
            val progLabel = monoLabel("", 10.5f, p.textDim).apply { visibility = View.GONE }
            card.addView(progLabel, card.linLp(MATCH, WRAP, t = 6))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            card.addView(buttons, card.linLp(MATCH, WRAP, t = 14))
            cards.addView(card, cards.linLp(MATCH, WRAP, b = 12))
            views[m.id] = CardViews(badge, prog, progLabel, buttons)
            fillButtons(m, active)
        }
        InstallState.current?.let { if (it.running) onProgress(it) }
    }

    private fun fillButtons(m: ModelInfo, active: String?) {
        val p = palette
        val v = views[m.id] ?: return
        v.buttons.removeAllViews()
        val busy = InstallState.current?.let { it.running && it.modelId == m.id } == true
        val isActive = m.installed && m.id == active
        v.badge.visibility = if (isActive) View.VISIBLE else if (m.installed) View.VISIBLE else View.GONE
        v.badge.setMonoText(if (isActive) "In use" else "Downloaded")
        (v.badge.background as android.graphics.drawable.GradientDrawable).setColor(if (isActive) p.accent else p.withAlpha(p.textFaint, 0.6f))
        val lp = { LinearLayout.LayoutParams(0, WRAP, 1f) }
        when {
            busy -> v.buttons.addView(pill(p, "Pause download", Icon.CLOSE) { ModelInstallService.cancel() }, lp())
            !m.installed -> {
                val label = if (m.partialBytes > 0) "Resume (${Format.megabytes(m.partialBytes)} done)" else "Download ${Format.megabytes(m.downloadBytes)}"
                v.buttons.addView(pill(p, label, Icon.DOWNLOAD, GlassDrawable.Style.ACCENT) { download(m) }, lp())
                if (m.partialBytes > 0) {
                    v.buttons.addView(View(this), LinearLayout.LayoutParams(dpi(10), 1))
                    v.buttons.addView(pill(p, "Discard", Icon.TRASH) { delete(m) })
                }
            }
            else -> {
                if (isActive) v.buttons.addView(pill(p, "In use", Icon.CHECK) {}, lp())
                else v.buttons.addView(pill(p, "Use this model", Icon.CHECK, GlassDrawable.Style.ACCENT) { use(m) }, lp())
                v.buttons.addView(View(this), LinearLayout.LayoutParams(dpi(10), 1))
                v.buttons.addView(pill(p, "Delete", Icon.TRASH) { delete(m) })
            }
        }
    }

    private fun onProgress(pr: InstallState.Progress) {
        val v = views[pr.modelId] ?: return
        when (pr.phase) {
            "done", "cancelled", "error" -> {
                showingPause -= pr.modelId
                v.progress.visibility = View.GONE
                v.progressLabel.visibility = View.GONE
                if (pr.phase == "error") GlassToast.show(this, "Download failed: ${pr.error}", bottomDp = 40)
                if (pr.phase == "done") {
                    // You just chose to download it, so start using it right away.
                    if (Prefs.activeModel(this) != pr.modelId) SttManager.release()
                    Prefs.setActiveModel(this, pr.modelId)
                    val name = models.firstOrNull { it.id == pr.modelId }?.name ?: "the new model"
                    GlassToast.show(this, "Ready. Verba now uses $name.", bottomDp = 40)
                    renderCloud()
                }
                reload()
            }
            else -> {
                v.progress.visibility = View.VISIBLE
                v.progressLabel.visibility = View.VISIBLE
                v.progress.indeterminate = pr.total <= 0
                v.progress.fraction = pr.fraction
                v.progressLabel.text = (when (pr.phase) {
                    "download" -> "Downloading ${Format.megabytes(pr.done)} of ${Format.megabytes(pr.total)} · ${(pr.fraction * 100).toInt()}%"
                    "verify" -> "Checking the download… ${(pr.fraction * 100).toInt()}%"
                    "extract" -> "Unpacking… ${(pr.fraction * 100).toInt()}%"
                    "extras" -> "Downloading voice detector…"
                    else -> "Starting…"
                }).uppercase()
                if (pr.modelId !in showingPause) {
                    showingPause += pr.modelId
                    models.firstOrNull { it.id == pr.modelId }?.let { fillButtons(it, Prefs.activeModel(this)) }
                }
            }
        }
    }

    private fun download(m: ModelInfo) {
        if (InstallState.current?.running == true) {
            GlassToast.show(this, "Another model is downloading", bottomDp = 40); return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 51)
        }
        val metered = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val start = {
            ModelInstallService.start(this, m.id)
            InstallState.post(InstallState.Progress(m.id, "starting"))
        }
        if (metered) {
            GlassDialog.confirm(
                this, "Use mobile data?",
                "${m.name} needs ${Format.megabytes(m.downloadBytes - m.partialBytes)}. You can pause and resume any time.",
                "Download",
            ) { start() }
        } else start()
    }

    private fun use(m: ModelInfo) {
        Prefs.setActiveModel(this, m.id)
        SttManager.release()
        render()
        renderCloud()
        GlassToast.show(this, "${m.name} will be used for speech", bottomDp = 40)
    }

    private fun delete(m: ModelInfo) {
        GlassDialog.confirm(
            this, "Delete ${m.name}?",
            if (m.installed) "Frees about ${m.installedMb} MB. You can download it again later." else "Removes the partial download.",
            "Delete", destructive = true,
        ) {
            if (Prefs.activeModel(this) == m.id) Prefs.setActiveModel(this, null)
            SttManager.release()
            Backend.async({ Backend.deleteModel(m.id) }) { reload() }
        }
    }
    // ===================================================================== Gemini
    private fun renderCloud() {
        val p = palette
        val c = cloudCard
        c.removeAllViews()
        val hasKey = Secrets.hasGeminiKey(this)
        val inUse = hasKey && Prefs.activeModel(this) == Prefs.GEMINI

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label("Gemini", 20f, p.text, 700))
        names.addView(monoLabel("Google · cloud · your key", 10.5f, p.textDim), names.linLp(WRAP, WRAP, t = 2))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (hasKey) {
            head.addView(monoLabel(if (inUse) "In use" else "Key saved", 10f, p.onAccent, bold = true).apply {
                setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12); setColor(if (inUse) p.accent else p.withAlpha(p.textFaint, 0.6f))
                }
            })
        }
        c.addView(head)
        c.addView(label("Strong on Hinglish, names and noisy audio. Needs internet.", 15f, p.text, 500), c.linLp(MATCH, WRAP, t = 12))
        c.addView(label("Get a key at aistudio.google.com/apikey (tap here). Google has a free tier; " +
            "usage limits and any charges are on your Google account. The key is stored encrypted on this phone only.",
            13f, p.textDim, 400).apply {
            setLineSpacing(0f, 1.15f)
            setOnClickListener {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))) }
            }
        }, c.linLp(MATCH, WRAP, t = 4))

        // ---- key
        if (!hasKey) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                glass(p, 22f, GlassDrawable.Style.STRONG)
                padDp(16, 0, 6, 0)
                minimumHeight = dpi(48)
            }
            val keyField = field(p, "Paste your Gemini API key", 15f).apply {
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Fonts.mono
                setText(keyDraft)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { keyDraft = s?.toString().orEmpty() }
                })
            }
            box.addView(keyField, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(pill(p, "Save", Icon.CHECK, GlassDrawable.Style.ACCENT, heightDp = 38) { saveKey(keyField.text.toString()) })
            c.addView(box, c.linLp(MATCH, WRAP, t = 14))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(monoLabel("Key  ${Secrets.geminiKeyHint(this) ?: ""}", 11f, p.textDim), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(pill(p, "Remove", Icon.TRASH, heightDp = 38) { removeKey() })
            c.addView(row, c.linLp(MATCH, WRAP, t = 14))
        }

        // ---- model
        c.addView(monoLabel("Model", 11f, p.text, bold = true), c.linLp(WRAP, WRAP, t = 16, b = 8))
        val current = GeminiModels.byId(Prefs.geminiModel(this))
        val modelNote = label(current.note, 13f, p.textDim, 400)
        c.addView(Segmented(this, p, GeminiModels.ALL.map { it.label }, GeminiModels.ALL.indexOf(current)) { i ->
            val m = GeminiModels.ALL[i]
            Prefs.setGeminiModel(this, m.id)
            modelNote.text = m.note
        }, c.linLp(MATCH, WRAP))
        c.addView(modelNote, c.linLp(MATCH, WRAP, t = 8))

        // ---- names and words
        c.addView(monoLabel("Names and words to listen for", 11f, p.text, bold = true), c.linLp(WRAP, WRAP, t = 16, b = 8))
        val wordsBox = FrameLayout(this).apply { glass(p, 20f, GlassDrawable.Style.STRONG); padDp(16, 12, 16, 12) }
        val words = field(p, "Amit, Matheran, DMart, lassi…", 15f).apply {
            setText(Prefs.geminiWords(this@SettingsActivity))
            minLines = 1
            maxLines = 4
            setOnFocusChangeListener { _, has -> if (!has) Prefs.setGeminiWords(this@SettingsActivity, text.toString()) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { Prefs.setGeminiWords(this@SettingsActivity, s?.toString().orEmpty()) }
            })
        }
        wordsBox.addView(words)
        c.addView(wordsBox, c.linLp(MATCH, WRAP))
        c.addView(label("Separate with commas. Helps Gemini spell people, places and shops correctly.", 12f, p.textFaint, 400), c.linLp(MATCH, WRAP, t = 6))

        // ---- actions
        if (hasKey) {
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = { LinearLayout.LayoutParams(0, WRAP, 1f) }
            if (inUse) buttons.addView(pill(p, "In use", Icon.CHECK) {}, lp())
            else buttons.addView(pill(p, "Use Gemini", Icon.CHECK, GlassDrawable.Style.ACCENT) { useGemini() }, lp())
            buttons.addView(View(this), LinearLayout.LayoutParams(dpi(10), 1))
            buttons.addView(pill(p, "Test", Icon.MIC) { testGemini() }, lp())
            c.addView(buttons, c.linLp(MATCH, WRAP, t = 16))
        }
    }

    private fun saveKey(raw: String) {
        val key = raw.trim().replace(Regex("\\s+"), "")
        if (key.length < 20) {
            GlassToast.show(this, "That does not look like a Gemini API key", bottomDp = 40); return
        }
        Backend.async({ Secrets.setGeminiKey(this, key); Secrets.hasGeminiKey(this) }) { r ->
            if (isDestroyed) return@async
            if (r.getOrNull() != true) {
                GlassToast.show(this, "Could not store the key: ${r.exceptionOrNull()?.message ?: "keystore error"}", bottomDp = 40)
                return@async
            }
            keyDraft = ""
            currentFocus?.let { v ->
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            }
            renderCloud()
            testGemini()
        }
    }

    private fun removeKey() {
        GlassDialog.confirm(this, "Remove Gemini key?", "Verba will forget the key. Speech goes back to your on-device model.", "Remove", destructive = true) {
            Secrets.setGeminiKey(this, null)
            if (Prefs.activeModel(this) == Prefs.GEMINI) {
                Prefs.setActiveModel(this, models.firstOrNull { it.installed }?.id)
                SttManager.release()
            }
            render()
            renderCloud()
        }
    }

    private fun useGemini() {
        Prefs.setActiveModel(this, Prefs.GEMINI)
        SttManager.release()
        render()
        renderCloud()
        GlassToast.show(this, "Gemini will be used for speech. Recordings are sent to Google.", bottomDp = 40)
    }

    private fun testGemini() {
        val key = Secrets.geminiKey(this) ?: return
        val model = GeminiModels.byId(Prefs.geminiModel(this))
        GlassToast.show(this, "Checking your key with ${model.label}…", bottomDp = 40)
        Backend.async({ GeminiClient.test(key, model.id) }) { r ->
            if (isDestroyed) return@async
            val problem = r.getOrElse { it.message ?: it.toString() }
            if (problem == null) GlassToast.show(this, "Gemini works with this key and model", bottomDp = 40)
            else GlassDialog.confirm(this, "Gemini did not work", problem, "OK", plainTitle = true) {}
        }
    }
}
