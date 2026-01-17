package com.nervesparks.iris.rag.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nervesparks.iris.docs.DocumentTextExtractor
import com.nervesparks.iris.irisapp.ServiceLocator
import com.nervesparks.iris.rag.ingest.Chunker
import com.nervesparks.iris.rag.ingest.TextNormalize
import com.nervesparks.iris.rag.storage.LocalChunk
import com.nervesparks.iris.rag.storage.LocalDoc
import com.nervesparks.iris.rag.util.FloatPacking
import java.io.File
import java.util.UUID
import kotlin.math.max

class IndexDocumentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)

        val docId = inputData.getString(KEY_DOC_ID) ?: return Result.failure()
        val uriStr = inputData.getString(KEY_URI) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: "Document"
        val mime = inputData.getString(KEY_MIME) ?: "application/octet-stream"
        val sizeBytes = inputData.getLong(KEY_SIZE, 0L)
        val createdAt = inputData.getLong(KEY_CREATED_AT, System.currentTimeMillis())

        val store = ServiceLocator.localRagStore

        // Mark indexing
        store.writeDocMeta(
            LocalDoc(
                docId = docId,
                uri = uriStr,
                name = name,
                mime = mime,
                sizeBytes = sizeBytes,
                createdAt = createdAt,
                status = "INDEXING",
                error = null
            )
        )

        if (!ServiceLocator.ensureEmbeddingReady(applicationContext)) {
            return failDoc(
                docId, uriStr, name, mime, sizeBytes, createdAt,
                "Embedding model not downloaded. Download it from Settings → Models."
            )
        }

        val embedder = ServiceLocator.embedder

        return try {
            val uri = Uri.parse(uriStr)

            // ✅ extractor now throws if empty/low-quality
            val extracted = DocumentTextExtractor.extractTextFromUri(
                context = applicationContext,
                uri = uri
            )

            var normalized = TextNormalize.normalize(extracted).trim()
            if (normalized.isBlank()) {
                return failDoc(
                    docId, uriStr, name, mime, sizeBytes, createdAt,
                    "No text extracted (empty after normalize)."
                )
            }

            // ✅ second safety dedupe (removes repeated headers that survive normalize)
            normalized = removeRepeatingLines(normalized)

            // ✅ If still too small, fail fast to avoid garbage indexing
            if (normalized.length < 350) {
                return failDoc(
                    docId, uriStr, name, mime, sizeBytes, createdAt,
                    "Extracted text too small after cleanup. PDF may be scanned or layout is unsupported."
                )
            }

            val chunks = Chunker.chunkText(
                normalized,
                targetChars = 700,   // ✅ Reduced from 900 for finer-grained retrieval
                overlapChars = 300   // ✅ Increased from 250 for better context continuity
            )

            if (chunks.isEmpty()) {
                return failDoc(
                    docId, uriStr, name, mime, sizeBytes, createdAt,
                    "Chunker produced 0 chunks."
                )
            }

            Log.i(TAG, "Created ${chunks.size} chunks for docId=$docId name=$name")

            val localChunks = ArrayList<LocalChunk>(chunks.size)
            val allEmbBytes = ByteArrayOutput()

            for (c in chunks) {
                val emb = embedder.embed(c.text)
                val chunkId = UUID.randomUUID().toString()

                localChunks.add(
                    LocalChunk(
                        chunkId = chunkId,
                        chunkIndex = c.index,
                        text = c.text
                    )
                )

                allEmbBytes.write(FloatPacking.floatsToBytes(emb))
            }

            store.writeChunksAndEmbeddings(
                docId = docId,
                chunks = localChunks,
                embeddingsBytes = allEmbBytes.toByteArray()
            )

            store.writeDocMeta(
                LocalDoc(
                    docId = docId,
                    uri = uriStr,
                    name = name,
                    mime = mime,
                    sizeBytes = sizeBytes,
                    createdAt = createdAt,
                    status = "READY",
                    error = null
                )
            )

            runCatching { deleteLocalFileIfPossible(uri) }

            Log.i(TAG, "Indexed docId=$docId name=$name chunks=${chunks.size}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "IndexDocumentWorker FAILED docId=$docId uri=$uriStr name=$name", e)
            failDoc(
                docId, uriStr, name, mime, sizeBytes, createdAt,
                (e.message ?: e.toString())
            )
        }
    }

    private fun failDoc(
        docId: String,
        uriStr: String,
        name: String,
        mime: String,
        sizeBytes: Long,
        createdAt: Long,
        error: String
    ): Result {
        val store = ServiceLocator.localRagStore
        store.writeDocMeta(
            LocalDoc(
                docId = docId,
                uri = uriStr,
                name = name,
                mime = mime,
                sizeBytes = sizeBytes,
                createdAt = createdAt,
                status = "FAILED",
                error = error
            )
        )
        return Result.failure(workDataOf("error" to error))
    }

    private fun deleteLocalFileIfPossible(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val f = File(path)
        if (f.exists()) {
            val ok = f.delete()
            Log.i(TAG, "Deleted local copy: $path ok=$ok")
        }
    }

    /**
     * Removes repeated short lines that appear many times (resume name/header spam).
     */
    private fun removeRepeatingLines(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 12) return text

        val freq = HashMap<String, Int>()
        for (l in lines) {
            val key = l.lowercase().replace(Regex("\\s+"), " ")
            freq[key] = (freq[key] ?: 0) + 1
        }

        val filtered = lines.filter { l ->
            val key = l.lowercase().replace(Regex("\\s+"), " ")
            val count = freq[key] ?: 0
            val isShort = key.length <= 60
            val isRepeated = count >= 3
            !(isShort && isRepeated)
        }

        val out = filtered.joinToString("\n")
        return if (out.length >= max(120, text.length / 4)) out else text
    }

    // Helper: build big ByteArray without reallocating too much
    private class ByteArrayOutput {
        private var buf = ByteArray(1024 * 16)
        private var size = 0

        fun write(bytes: ByteArray) {
            ensureCapacity(size + bytes.size)
            System.arraycopy(bytes, 0, buf, size, bytes.size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensureCapacity(needed: Int) {
            if (needed <= buf.size) return
            var n = buf.size
            while (n < needed) n *= 2
            buf = buf.copyOf(n)
        }
    }

    companion object {
        private const val TAG = "IndexDocumentWorker"

        const val KEY_DOC_ID = "doc_id"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val KEY_SIZE = "size"
        const val KEY_CREATED_AT = "created_at"
    }
}
