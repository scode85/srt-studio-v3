package com.srtstudio

import java.io.File
import java.util.Locale

object SrtWriter {
    private val sb = StringBuilder()
    private var idx = 1

    fun reset() { sb.setLength(0); idx = 1 }

    fun add(startMs: Long, endMs: Long, text: String) {
        if (text.isBlank()) return
        sb.append(idx++).append('\n')
        sb.append(fmt(startMs)).append(" --> ").append(fmt(endMs)).append('\n')
        sb.append(text.trim()).append("\n\n")
    }

    fun save(file: File): Int {
        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
        return idx - 1
    }

    private fun fmt(ms: Long): String {
        val m = if (ms < 0) 0L else ms
        val h = m / 3600000
        val mi = (m % 3600000) / 60000
        val s = (m % 60000) / 1000
        val mm = m % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, mi, s, mm)
    }
}
