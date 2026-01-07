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
     * ✅ Primary API used by Worker/Repo
     * Throws with a real reason if extraction fails.
     */
    fun extractTextFromUri(
        context: Context,
        uri: Uri,
        maxBytes: Int = 7_500_000,
        maxChars: Int = 250_000,
    ): String {
        val resolver = context.contentResolver
        val meta = try { queryMeta(resolver, uri) } catch (_: Throwable) {
            Meta("document", "", 0L)
        }

        Log.d(TAG, "extractTextFromUri start name=${meta.displayName} mime=${meta.mime} uri=$uri")

        val out = extractText(
            context = context,
            resolver = resolver,
            uri = uri,
            maxBytes = maxBytes,
            maxChars = maxChars
        )?.trim().orEmpty()

        if (out.isBlank()) {
            throw IllegalStateException(
                "Extraction returned empty. name=${meta.displayName} mime=${meta.mime} uri=$uri"
            )
        }

        Log.d(TAG, "extractTextFromUri OK chars=${out.length} name=${meta.displayName}")
        return out
    }

    private fun extractText(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        maxBytes: Int,
        maxChars: Int,
    ): String? {
        val meta = try {
            queryMeta(resolver, uri)
        } catch (_: Throwable) {
            Meta(displayName = "document", mime = "", sizeBytes = 0L)
        }

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
                try { ensurePdfBoxInit(context) } catch (_: Throwable) {}
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
                try { doc.close() } catch (_: Throwable) {}
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
                try { wb.close() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "XLSX extraction failed", t)
            null
        }
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
