package com.nervesparks.iris

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.nio.charset.Charset
import kotlin.math.min

data class UserDoc(
    val id: String,
    val name: String,
    val uri: String,
    val mime: String,
    val sizeBytes: Long,
    val text: String,
    val chunks: List<String>
)

internal fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else null
    } catch (_: Exception) {
        null
    } finally {
        cursor?.close()
    }
}

internal fun querySizeBytes(resolver: ContentResolver, uri: Uri): Long {
    var cursor: Cursor? = null
    return try {
        cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0) cursor.getLong(idx) else 0L
        } else 0L
    } catch (_: Exception) {
        0L
    } finally {
        cursor?.close()
    }
}

/**
 * Reads text from a SAF Uri.
 * Returns null if file is too large or can't be decoded / looks binary.
 */
internal fun readTextFromUri(
    resolver: ContentResolver,
    uri: Uri,
    maxBytes: Int = 2_500_000,
    charset: Charset = Charsets.UTF_8
): String? {
    return try {
        resolver.openInputStream(uri)?.use { input ->
            val bis = BufferedInputStream(input)
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = bis.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            val bytes = out.toByteArray()
            val text = bytes.toString(charset)

            // Rough binary detection
            val nulCount = text.count { it == '\u0000' }
            if (nulCount > 0) return null

            text
        }
    } catch (_: Exception) {
        null
    }
}

object DocText {

    fun chunk(text: String, chunkSize: Int, overlap: Int): List<String> {
        val clean = text.replace("\r\n", "\n")
        if (clean.length <= chunkSize) return listOf(clean)

        val out = ArrayList<String>()
        var i = 0
        while (i < clean.length) {
            val end = min(i + chunkSize, clean.length)
            out += clean.substring(i, end)
            if (end == clean.length) break
            i = (end - overlap).coerceAtLeast(0)
        }
        return out
    }

    private fun tokenize(s: String): List<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()

    private fun scoreChunk(tokens: List<String>, chunk: String): Int {
        val c = chunk.lowercase()
        var score = 0
        for (t in tokens) {
            if (c.contains(t)) score += 1
        }
        return score
    }

    fun buildContext(
        docs: List<UserDoc>,
        query: String,
        maxChars: Int = 6000,
        topK: Int = 5
    ): String? {
        if (docs.isEmpty()) return null
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return null

        data class Hit(val docName: String, val chunkIndex: Int, val text: String, val score: Int)

        val hits = mutableListOf<Hit>()
        for (d in docs) {
            d.chunks.forEachIndexed { idx, ch ->
                val s = scoreChunk(tokens, ch)
                if (s > 0) hits += Hit(d.name, idx, ch, s)
            }
        }

        val top = hits
            .sortedByDescending { it.score }
            .take(topK)

        if (top.isEmpty()) return null

        val sb = StringBuilder()
        sb.append(
            """You have access to USER-PROVIDED DOCUMENT EXCERPTS.
Use ONLY the excerpts to answer the user's question when possible.
If the answer isn't in the excerpts, say you don't have enough information.

When you use an excerpt, cite it like: (source: <document name>).

DOCUMENT EXCERPTS:
"""
        )

        var used = 0
        for (h in top) {
            val block = "\n\n[${h.docName} :: chunk ${h.chunkIndex}]\n${h.text.trim()}\n"
            if (used + block.length > maxChars) break
            sb.append(block)
            used += block.length
        }

        return sb.toString()
    }
}
