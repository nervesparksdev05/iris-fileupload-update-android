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
import java.util.UUID
import kotlin.math.abs

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
        val embedder = ServiceLocator.embedder

        // mark indexing
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

        return try {
            val uri = Uri.parse(uriStr)

            val extracted = DocumentTextExtractor.extractTextFromUri(
                context = applicationContext,
                uri = uri
            )

            val normalized = TextNormalize.normalize(extracted)
            if (normalized.isBlank()) {
                store.writeDocMeta(
                    LocalDoc(
                        docId = docId,
                        uri = uriStr,
                        name = name,
                        mime = mime,
                        sizeBytes = sizeBytes,
                        createdAt = createdAt,
                        status = "FAILED",
                        error = "No text extracted (empty after normalize)."
                    )
                )
                return Result.failure(workDataOf("error" to "No text extracted"))
            }

            val chunks = Chunker.chunkText(normalized, targetChars = 1400, overlapChars = 250)
            if (chunks.isEmpty()) {
                store.writeDocMeta(
                    LocalDoc(
                        docId = docId,
                        uri = uriStr,
                        name = name,
                        mime = mime,
                        sizeBytes = sizeBytes,
                        createdAt = createdAt,
                        status = "FAILED",
                        error = "Chunker produced 0 chunks."
                    )
                )
                return Result.failure(workDataOf("error" to "0 chunks"))
            }

            val localChunks = ArrayList<LocalChunk>(chunks.size)
            val allEmbBytes = ByteArrayOutput()

            for (c in chunks) {
                val emb = embedder.embed(c.text) // FloatArray
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

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "IndexDocumentWorker FAILED docId=$docId uri=$uriStr name=$name", e)

            store.writeDocMeta(
                LocalDoc(
                    docId = docId,
                    uri = uriStr,
                    name = name,
                    mime = mime,
                    sizeBytes = sizeBytes,
                    createdAt = createdAt,
                    status = "FAILED",
                    error = (e.message ?: e.toString())
                )
            )
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }

    // Small helper to build big ByteArray without reallocating too much
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
