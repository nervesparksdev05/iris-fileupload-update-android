package com.nervesparks.iris.docs

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

// PDF (pdfbox-android)
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

// DOCX / XLSX (Apache POI)
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument

object DocumentTextExtractor {

    private const val TAG = "DocumentTextExtractor"

    data class Meta(
        val displayName: String,
        val mime: String,
        val sizeBytes: Long,
    )

    @Volatile
    private var pdfBoxInited: Boolean = false

    fun ensurePdfBoxInit(context: Context) {
        if (pdfBoxInited) return
        synchronized(this) {
            if (!pdfBoxInited) {
                PDFBoxResourceLoader.init(context.applicationContext)
                pdfBoxInited = true
            }
        }
    }

    fun queryMeta(resolver: ContentResolver, uri: Uri): Meta {
        val mime = try {
            resolver.getType(uri) ?: ""
        } catch (_: Throwable) {
            ""
        }

        var name = uri.lastPathSegment ?: "document"
        var size = -1L

        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        } catch (_: Throwable) {
        } finally {
            try { cursor?.close() } catch (_: Throwable) {}
        }

        return Meta(
            displayName = name,
            mime = mime,
            sizeBytes = size.coerceAtLeast(0L)
        )
    }

    /**
     * Primary API used by Worker/Repo.
     * Throws with a real reason if extraction fails or is low-quality.
     */
    fun extractTextFromUri(
        context: Context,
        uri: Uri,
        maxBytes: Int = 7_500_000,
        maxChars: Int = 250_000,
    ): String {
        val resolver = context.contentResolver
        val meta = runCatching { queryMeta(resolver, uri) }
            .getOrElse { Meta("document", "", 0L) }

        Log.d(TAG, "extractTextFromUri start name=${meta.displayName} mime=${meta.mime} uri=$uri")

        val raw = extractText(
            context = context,
            resolver = resolver,
            uri = uri,
            maxBytes = maxBytes,
            maxChars = maxChars
        )?.trim().orEmpty()

        if (raw.isBlank()) {
            throw IllegalStateException("Extraction returned empty. name=${meta.displayName} mime=${meta.mime} uri=$uri")
        }

        // ✅ Clean repeated header/footer noise (very common in resumes)
        val cleaned = removeRepeatingLines(raw)

        // ✅ Quality gate: prevent indexing garbage that causes repeated-name answers
        val q = quality(cleaned)
        if (q.tooShort) {
            throw IllegalStateException(
                "Extraction too small (${q.chars} chars). Likely scanned/image PDF or unsupported layout. name=${meta.displayName}"
            )
        }
        if (q.tooRepetitive) {
            throw IllegalStateException(
                "Extraction too repetitive (uniqueLineRatio=${"%.2f".format(q.uniqueLineRatio)}). Likely header-only text. name=${meta.displayName}"
            )
        }

        Log.d(TAG, "extractTextFromUri OK chars=${cleaned.length} name=${meta.displayName}")
        return cleaned.take(maxChars)
    }

    private fun extractText(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        maxBytes: Int,
        maxChars: Int,
    ): String? {
        val meta = runCatching { queryMeta(resolver, uri) }
            .getOrElse { Meta(displayName = "document", mime = "", sizeBytes = 0L) }

        val name = meta.displayName
        val mime = meta.mime
        val lower = name.lowercase()

        return when {
            mime.startsWith("text/") ||
                    mime == "application/json" ||
                    lower.endsWith(".txt") ||
                    lower.endsWith(".md") ||
                    lower.endsWith(".csv") ||
                    lower.endsWith(".json") ||
                    lower.endsWith(".xml") -> {
                openInputStreamCompat(context, uri)?.use { it.readCappedUtf8(maxBytes, maxChars) }
            }

            mime == "application/pdf" || lower.endsWith(".pdf") -> {
                runCatching { ensurePdfBoxInit(context) }
                openInputStreamCompat(context, uri)?.use { input ->
                    extractPdf(BufferedInputStream(input), maxChars)
                }
            }

            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    lower.endsWith(".docx") -> {
                openInputStreamCompat(context, uri)?.use { input ->
                    extractDocx(BufferedInputStream(input), maxChars)
                }
            }

            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                    lower.endsWith(".xlsx") -> {
                openInputStreamCompat(context, uri)?.use { input ->
                    extractXlsx(BufferedInputStream(input), maxChars)
                }
            }

            else -> {
                Log.w(TAG, "Unsupported type name=$name mime=$mime uri=$uri")
                null
            }
        }
    }

    private fun openInputStreamCompat(context: Context, uri: Uri): InputStream? {
        return try {
            when (uri.scheme) {
                "file" -> {
                    val f = File(uri.path ?: return null)
                    FileInputStream(f)
                }
                else -> context.contentResolver.openInputStream(uri)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "openInputStreamCompat failed uri=$uri", t)
            null
        }
    }

    private fun extractPdf(input: InputStream, maxChars: Int): String? {
        return try {
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(doc).orEmpty().trim().take(maxChars)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PDF extraction failed", t)
            null
        }
    }

    private fun extractDocx(input: InputStream, maxChars: Int): String? {
        return try {
            val doc = XWPFDocument(input)
            try {
                val sb = StringBuilder()
                for (p in doc.paragraphs) {
                    val t = p.text
                    if (!t.isNullOrBlank()) {
                        sb.append(t.trim()).append('\n')
                        if (sb.length >= maxChars) break
                    }
                }
                sb.toString().trim().take(maxChars)
            } finally {
                runCatching { doc.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DOCX extraction failed", t)
            null
        }
    }

    private fun extractXlsx(input: InputStream, maxChars: Int): String? {
        return try {
            val wb = XSSFWorkbook(input)
            try {
                val fmt = DataFormatter()
                val sb = StringBuilder()

                for (s in 0 until wb.numberOfSheets) {
                    val sheet = wb.getSheetAt(s)
                    sb.append("Sheet: ").append(sheet.sheetName).append('\n')

                    for (row in sheet) {
                        val rowVals = ArrayList<String>(row.lastCellNum.coerceAtLeast(0).toInt())

                        for (cell in row) {
                            val v = when (cell.cellType) {
                                CellType.STRING -> cell.stringCellValue
                                CellType.NUMERIC -> fmt.formatCellValue(cell)
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                CellType.FORMULA -> fmt.formatCellValue(cell)
                                else -> ""
                            }
                            rowVals += v
                        }

                        val line = rowVals.joinToString("\t").trimEnd()
                        if (line.isNotBlank()) {
                            sb.append(line).append('\n')
                            if (sb.length >= maxChars) break
                        }
                    }

                    sb.append('\n')
                    if (sb.length >= maxChars) break
                }

                sb.toString().trim().take(maxChars)
            } finally {
                runCatching { wb.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "XLSX extraction failed", t)
            null
        }
    }

    // ---------------------------
    // ✅ Noise cleanup & quality
    // ---------------------------

    private data class Quality(
        val chars: Int,
        val lines: Int,
        val uniqueLines: Int,
        val uniqueLineRatio: Double,
        val tooShort: Boolean,
        val tooRepetitive: Boolean
    )

    private fun quality(text: String): Quality {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val unique = lines.map { it.lowercase().replace(Regex("\\s+"), " ") }.toSet()
        val ratio = if (lines.isEmpty()) 0.0 else unique.size.toDouble() / lines.size.toDouble()

        val chars = text.length
        val tooShort = chars < 350 // resumes should be larger than this if extraction succeeded
        val tooRepetitive = lines.size >= 10 && ratio < 0.35

        return Quality(
            chars = chars,
            lines = lines.size,
            uniqueLines = unique.size,
            uniqueLineRatio = ratio,
            tooShort = tooShort,
            tooRepetitive = tooRepetitive
        )
    }

    /**
     * Removes repeated short lines (headers/footers) that appear many times.
     * This fixes "Name Name Name" chunk domination.
     */
    private fun removeRepeatingLines(text: String): String {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.size < 12) return text

        val freq = HashMap<String, Int>()
        for (l in lines) {
            val key = l.lowercase().replace(Regex("\\s+"), " ")
            freq[key] = (freq[key] ?: 0) + 1
        }

        val filtered = lines.filter { l ->
            val key = l.lowercase().replace(Regex("\\s+"), " ")
            val count = freq[key] ?: 0

            // drop repeated short lines (headers like candidate name, title, phone)
            val isShort = key.length <= 60
            val isRepeated = count >= 3

            !(isShort && isRepeated)
        }

        // If filtering removed too much, keep original
        val out = filtered.joinToString("\n")
        return if (out.length >= max(120, text.length / 4)) out else text
    }
}

private fun InputStream.readCappedUtf8(maxBytes: Int, maxChars: Int): String {
    val buf = ByteArray(8192)
    val out = StringBuilder()
    var readTotal = 0

    while (true) {
        val toRead = (maxBytes - readTotal).coerceAtMost(buf.size)
        if (toRead <= 0) break

        val n = read(buf, 0, toRead)
        if (n <= 0) break

        readTotal += n
        out.append(String(buf, 0, n, Charsets.UTF_8))

        if (out.length >= maxChars) break
    }

    return out.toString().take(maxChars)
}
