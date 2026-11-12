package com.verba.app.data

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("verba", Context.MODE_PRIVATE)

    /** "auto", "hi" or "en". */
    fun language(c: Context): String = sp(c).getString("language", "auto") ?: "auto"
    fun setLanguage(c: Context, v: String) = sp(c).edit().putString("language", v).apply()

    /** Id of the speech model to use, or null to pick any downloaded one. */
    fun activeModel(c: Context): String? = sp(c).getString("active_model", null)
    fun setActiveModel(c: Context, v: String?) = sp(c).edit().putString("active_model", v).apply()

    /** Turn every new voice recording into text right after it is saved. On by default. */
    fun autoTranscribe(c: Context): Boolean = sp(c).getBoolean("auto_transcribe", true)
    fun setAutoTranscribe(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_transcribe", v).apply()

    // ---- voice notes waiting to be transcribed (survives the app being closed)
    /** note id -> attempts so far */
    fun pendingTranscriptions(c: Context): Map<String, Int> =
        (sp(c).getStringSet("pending_transcriptions", emptySet()) ?: emptySet()).mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: 0)
        }.toMap()

    @Synchronized
    fun setPendingTranscription(c: Context, id: String, attempts: Int?) {
        val m = pendingTranscriptions(c).toMutableMap()
        if (attempts == null) m.remove(id) else m[id] = attempts
        sp(c).edit().putStringSet("pending_transcriptions", m.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    // ---- colours: "auto" (phone theme, vivid; black & white for colourless wallpapers), "red", "mono"
    fun accentMode(c: Context): String = sp(c).getString("accent_mode", "auto") ?: "auto"
    fun setAccentMode(c: Context, v: String) = sp(c).edit().putString("accent_mode", v).apply()
    /** The accent colours the home-screen widget was last painted with. */
    fun widgetAccentKey(c: Context): String? = sp(c).getString("widget_accent_key", null)
    fun setWidgetAccentKey(c: Context, v: String) = sp(c).edit().putString("widget_accent_key", v).apply()

    // ---- Gemini (cloud, bring your own key). The key itself lives in [Secrets].
    const val GEMINI = "gemini"
    fun geminiModel(c: Context): String = sp(c).getString("gemini_model", GeminiModels.DEFAULT) ?: GeminiModels.DEFAULT
    fun setGeminiModel(c: Context, v: String) = sp(c).edit().putString("gemini_model", v).apply()
    /** Names and words to listen for, comma separated. */
    fun geminiWords(c: Context): String = sp(c).getString("gemini_words", "") ?: ""
    fun setGeminiWords(c: Context, v: String) = sp(c).edit().putString("gemini_words", v).apply()

    fun languageLabel(code: String) = when (code) {
        "hi" -> "हिन्दी"
        "en" -> "English"
        else -> "Auto"
    }

    fun nextLanguage(code: String) = when (code) {
        "auto" -> "hi"
        "hi" -> "en"
        else -> "auto"
    }
}
