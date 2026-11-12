package com.verba.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's Gemini API key encrypted with a key that never leaves the
 * phone's hardware keystore. The prefs file is not part of backups, and if the
 * keystore key is ever lost the stored value simply reads back as "no key".
 */
object Secrets {
    private const val ALIAS = "verba_secrets"
    private const val FILE = "verba_secrets"
    private const val GEMINI = "gemini_key"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun setGeminiKey(c: Context, value: String?) {
        if (value.isNullOrBlank()) { sp(c).edit().remove(GEMINI).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        sp(c).edit().putString(GEMINI, packed).apply()
    }

    fun geminiKey(c: Context): String? {
        val packed = sp(c).getString(GEMINI, null) ?: return null
        return try {
            val (iv, ct) = packed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    fun hasGeminiKey(c: Context) = geminiKey(c) != null

    /** "AIza…x7Qk" style preview so you can tell which key is saved. */
    fun geminiKeyHint(c: Context): String? = geminiKey(c)?.let { k ->
        if (k.length <= 10) "•••" else k.take(4) + "…" + k.takeLast(4)
    }
}
