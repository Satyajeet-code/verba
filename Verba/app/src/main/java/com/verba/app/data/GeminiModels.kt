package com.verba.app.data

/** Gemini models offered for cloud transcription (all take audio input). */
data class GeminiModel(
    val id: String,
    val label: String,
    val note: String,
    /** Google's dedicated speech-to-text model: uploaded file + transcription settings instead of a prompt. */
    val dedicated: Boolean = false,
)

object GeminiModels {
    const val DEFAULT = "gemini-3.8-flash"
    val ALL = listOf(
        GeminiModel("gemini-3.8-flash", "3.8 Flash", "Google's newest Flash model. Most accurate here."),
        GeminiModel("gemini-3.5-flash-lite", "Flash-Lite", "Faster and cheaper; good for clear speech."),
        GeminiModel("gemini-3.5-transcribe", "Transcribe", "Google's speech-to-text model (preview). Handles Hindi–English switching and uses your word list.", dedicated = true),
    )

    fun byId(id: String?): GeminiModel = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
