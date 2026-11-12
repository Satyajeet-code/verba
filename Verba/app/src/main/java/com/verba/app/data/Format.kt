package com.verba.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    fun stamp(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - ms
        if (diff < 60_000) return "Just now"
        if (diff < 3_600_000) return "${diff / 60_000} min ago"
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        if (sameDay(then, today)) return "Today $time"
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(then, today)) return "Yesterday $time"
        if (diff < 6L * 86_400_000) return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
        val sameYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        return SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }

    fun deletedAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
        val days = (now - ms) / 86_400_000
        return when {
            days < 1 -> "Deleted today"
            days == 1L -> "Deleted yesterday"
            else -> "Deleted $days days ago"
        }
    }

    fun langChip(lang: String) = when (lang) {
        "hi" -> "हिन्दी"
        "en" -> "English"
        "mixed" -> "Hinglish"
        else -> ""
    }

    fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
