package com.nervesparks.iris.rag

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nervesparks.iris.rag.embed.Embedder
import com.nervesparks.iris.rag.retrieval.VectorSearch
import com.nervesparks.iris.rag.storage.LocalDoc
import com.nervesparks.iris.rag.storage.LocalRagStore
import com.nervesparks.iris.rag.util.FloatPacking
import com.nervesparks.iris.rag.worker.IndexDocumentWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit

class RagRepository(
    private val context: Context,
    private val store: LocalRagStore,
    private val embedder: Embedder
) {
    // Emits current docs when collected (refresh by re-collecting).
    fun observeDocs(): Flow<List<LocalDoc>> = flow { emit(store.readAllDocs()) }

    suspend fun addDocuments(uris: List<Uri>) {
        for (uri in uris) {
            val meta = queryMeta(uri)
            val docId = store.newDocId()

            store.writeDocMeta(
                LocalDoc(
                    docId = docId,
                    uri = uri.toString(),
                    name = meta.name,
                    mime = meta.mime,
                    sizeBytes = meta.sizeBytes,
                    createdAt = System.currentTimeMillis(),
                    status = DocStatus.INDEXING.name, // ✅ enum-backed string
                    error = null
                )
            )

            enqueueIndexWorker(docId, uri, meta)
        }
    }

    suspend fun removeDocument(docId: String) {
        store.deleteDoc(docId)
    }

    suspend fun retrieve(query: String, topK: Int = 6): List<RetrievalHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val qEmb = embedder.embed(q) // expected normalized FloatArray

        val all = store.readAllChunksAndEmbeddings()
        if (all.isEmpty()) return emptyList()

        val scored = ArrayList<RetrievalHit>(all.size)
        for ((doc, chunk, embBytes) in all) {
            val cEmb = FloatPacking.bytesToFloats(embBytes)
            val score: Double = VectorSearch.dot(qEmb, cEmb) // ✅ Float

            if (score > 0.10f) {
                scored.add(
                    RetrievalHit(
                        docId = doc.docId,
                        docName = doc.name,
                        chunkId = chunk.chunkId,
                        chunkIndex = chunk.chunkIndex,
                        text = chunk.text,
                        score = score
                    )
                )
            }
        }

        return scored.sortedByDescending { it.score }.take(topK)
    }

    fun buildContextBlock(hits: List<RetrievalHit>, maxChars: Int = 6500): String? {
        if (hits.isEmpty()) return null

        val sb = StringBuilder()
        sb.appendLine("You have access to user-provided documents.")
        sb.appendLine("Use ONLY the excerpts below to answer.")
        sb.appendLine("Cite sources as [DocName#chunkIndex].")
        sb.appendLine("If the answer is not contained in the excerpts, say you don't know from the documents.")
        sb.appendLine()
        sb.appendLine("EXCERPTS:")

        var used = 0
        for (h in hits) {
            val header = "[${h.docName}#${h.chunkIndex}] "
            val body = h.text.trim().replace(Regex("\\s+"), " ")
            val block = header + body
            if (used + block.length + 2 > maxChars) break
            sb.appendLine(block)
            sb.appendLine()
            used += block.length + 2
        }

        return sb.toString().trim()
    }

    // ---------- WorkManager ----------

    private fun enqueueIndexWorker(docId: String, uri: Uri, meta: DocMeta) {
        val data = workDataOf(
            IndexDocumentWorker.KEY_DOC_ID to docId,
            IndexDocumentWorker.KEY_URI to uri.toString(),
            IndexDocumentWorker.KEY_NAME to meta.name,
            IndexDocumentWorker.KEY_MIME to meta.mime,
            IndexDocumentWorker.KEY_SIZE to meta.sizeBytes,
            IndexDocumentWorker.KEY_CREATED_AT to System.currentTimeMillis()
        )

        val req = OneTimeWorkRequestBuilder<IndexDocumentWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "index_doc_$docId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    // ---------- Meta ----------

    private data class DocMeta(val name: String, val mime: String, val sizeBytes: Long)

    private fun queryMeta(uri: Uri): DocMeta {
        // Handle file:// URIs (copied into app storage) without ContentResolver queries.
        if (uri.scheme == "file") {
            val path = uri.path.orEmpty()
            val f = java.io.File(path)
            val name = if (f.exists()) f.name else "Document"
            val size = if (f.exists()) f.length() else 0L
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "md"  -> "text/markdown"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }
            return DocMeta(name = name, mime = mime, sizeBytes = size)
        }

        val cr = context.contentResolver
        var name = "Document"
        var size = 0L
        val mime = cr.getType(uri) ?: "application/octet-stream"

        val cursor: Cursor? = cr.query(uri, null, null, null, null)
        cursor?.use {
            val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
            if (it.moveToFirst()) {
                if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = it.getLong(sizeIdx)
            }
        }
        return DocMeta(name = name, mime = mime, sizeBytes = size)
    }
}
