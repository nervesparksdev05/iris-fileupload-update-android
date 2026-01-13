package com.nervesparks.iris.rag

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nervesparks.iris.rag.embed.Embedder
import com.nervesparks.iris.rag.retrieval.VectorSearch
import com.nervesparks.iris.rag.storage.LocalChunk
import com.nervesparks.iris.rag.storage.LocalDoc
import com.nervesparks.iris.rag.storage.LocalRagStore
import com.nervesparks.iris.rag.worker.IndexDocumentWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RagRepository(
    private val context: Context,
    private val store: LocalRagStore,
    private val embedder: Embedder
) {
    companion object {
        private const val TAG = "RagRepository"
    }

    fun observeDocs(pollMs: Long = 1_000L): Flow<List<LocalDoc>> = flow {
        var last: List<LocalDoc>? = null
        while (true) {
            val now = store.readAllDocs()
            if (last == null || now != last) emit(now)
            last = now
            delay(pollMs)
        }
    }.distinctUntilChanged()

    fun snapshotDocs(): List<LocalDoc> = store.readAllDocs()

    fun fallbackTopChunksForDoc(docId: String, maxChunks: Int = 8): List<RetrievalHit> {
        if (maxChunks <= 0) return emptyList()
        val doc = store.readAllDocs().firstOrNull { it.docId == docId } ?: return emptyList()
        val chunks = store.readDocChunks(docId, maxChunks).sortedBy { it.chunkIndex }

        return chunks.map { c ->
            RetrievalHit(
                docId = doc.docId,
                docName = doc.name,
                chunkId = c.chunkId,
                chunkIndex = c.chunkIndex,
                text = c.text,
                score = 1.0
            )
        }
    }

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
                    status = DocStatus.INDEXING.name,
                    error = null
                )
            )

            invalidateCache(docId)
            enqueueIndexWorker(docId, uri, meta)
        }
    }

    suspend fun removeDocument(docId: String) {
        store.deleteDoc(docId)
        invalidateCache(docId)
    }

    suspend fun clearAllDocuments() {
        Log.i(TAG, "clearAllDocuments: Starting cleanup of all RAG data")

        val docs = store.readAllDocs()
        for (doc in docs) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork("index_${doc.docId}")
                store.deleteDoc(doc.docId)
                invalidateCache(doc.docId)

                runCatching {
                    val uri = Uri.parse(doc.uri)
                    if (uri.scheme == "file") {
                        uri.path?.let { p ->
                            val f = File(p)
                            if (f.exists() && f.absolutePath.contains("user_docs")) f.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete doc=${doc.docId}", e)
            }
        }

        runCatching {
            val userDocsDir = File(context.filesDir, "user_docs")
            if (userDocsDir.exists()) userDocsDir.listFiles()?.forEach { it.delete() }
        }

        Log.i(TAG, "clearAllDocuments: Cleanup complete")
    }

    private data class CachedDoc(
        val doc: LocalDoc,
        val chunks: List<LocalChunk>,
        val embeddings: ByteArray,
        val bytesPerEmb: Int,
        val chunksLastMod: Long,
        val embLastMod: Long
    )

    private val cache = LinkedHashMap<String, CachedDoc>(8, 0.75f, true)

    private fun invalidateCache(docId: String) {
        synchronized(cache) { cache.remove(docId) }
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private fun loadDocIntoCache(doc: LocalDoc, expectedDim: Int): CachedDoc? {
        val dir = store.docFolder(doc.docId)
        val chunksFile = File(dir, "chunks.jsonl")
        val embFile = File(dir, "embeddings.bin")
        if (!chunksFile.exists() || !embFile.exists()) return null

        val chunksLast = chunksFile.lastModified()
        val embLast = embFile.lastModified()

        synchronized(cache) {
            val cached = cache[doc.docId]
            if (cached != null && cached.chunksLastMod == chunksLast && cached.embLastMod == embLast) {
                val dim = cached.bytesPerEmb / 4
                if (dim == expectedDim) return cached
            }
        }

        val chunks = ArrayList<LocalChunk>()
        chunksFile.useLines { seq ->
            seq.filter { it.isNotBlank() }.forEach { line ->
                val jo = org.json.JSONObject(line)
                chunks.add(
                    LocalChunk(
                        chunkId = jo.getString("chunkId"),
                        chunkIndex = jo.getInt("chunkIndex"),
                        text = jo.getString("text")
                    )
                )
            }
        }

        val embBytes = embFile.readBytes()
        if (chunks.isEmpty() || embBytes.isEmpty()) return null

        val bytesPer = embBytes.size / chunks.size
        if (bytesPer * chunks.size != embBytes.size) return null

        val dim = bytesPer / 4
        if (dim != expectedDim) return null

        val cd = CachedDoc(
            doc = doc,
            chunks = chunks,
            embeddings = embBytes,
            bytesPerEmb = bytesPer,
            chunksLastMod = chunksLast,
            embLastMod = embLast
        )

        synchronized(cache) {
            cache[doc.docId] = cd
            while (cache.size > 8) {
                val it = cache.entries.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else break
            }
        }

        return cd
    }

    /**
     * ✅ Retrieval can be restricted to ONE doc using docIdFilter.
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 5,
        scoreThreshold: Double = 0.10,
        docIdFilter: String? = null
    ): List<RetrievalHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val qEmb = embedder.embed(q)
        if (qEmb.isEmpty()) return emptyList()

        val readyDocs = store.readAllDocs()
            .filter { it.status == "READY" }
            .let { docs ->
                if (docIdFilter.isNullOrBlank()) docs
                else docs.filter { it.docId == docIdFilter }
            }

        if (readyDocs.isEmpty()) return emptyList()

        val k = topK.coerceAtLeast(1)
        val heap = PriorityQueue<RetrievalHit>(k) { a, b -> a.score.compareTo(b.score) }

        for (doc in readyDocs) {
            val cached = loadDocIntoCache(doc, expectedDim = qEmb.size) ?: continue
            val bytesPer = cached.bytesPerEmb
            val dim = qEmb.size

            val nChunks = min(cached.chunks.size, cached.embeddings.size / bytesPer)
            for (i in 0 until nChunks) {
                val score = VectorSearch.dotPackedLE(qEmb, cached.embeddings, i * bytesPer, dim)
                if (score <= scoreThreshold) continue

                val chunk = cached.chunks[i]
                val hit = RetrievalHit(
                    docId = cached.doc.docId,
                    docName = cached.doc.name,
                    chunkId = chunk.chunkId,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text,
                    score = score
                )

                if (heap.size < k) heap.add(hit)
                else if (heap.peek().score < hit.score) {
                    heap.poll()
                    heap.add(hit)
                }
            }
        }

        val out = ArrayList<RetrievalHit>(heap.size)
        while (heap.isNotEmpty()) out.add(heap.poll())
        out.sortByDescending { it.score }
        return out
    }

    /**
     * Lightweight context block for model.
     */
    fun buildContextBlock(hits: List<RetrievalHit>, maxChars: Int = 1200): String? {
        if (hits.isEmpty()) return null

        val uniq = hits.distinctBy { "${it.docId}:${it.chunkId}:${it.chunkIndex}" }
        val hitsByDoc = uniq.groupBy { it.docName }

        val sb = StringBuilder()
        sb.appendLine(
            """
DOCUMENT CONTEXT (excerpts):
Use excerpts for factual claims. If missing, say "Not found in the document context."
When citing, mention: [DocName §ChunkNumber].

""".trimIndent()
        )

        var remaining = maxChars.coerceAtLeast(400)
        for ((docName, docHits) in hitsByDoc) {
            if (remaining <= 0) break
            sb.appendLine("### $docName")
            for (h in docHits.sortedByDescending { it.score }.take(6)) {
                val text = h.text.trim()
                if (text.isEmpty()) continue

                val block = "\n[${docName} §${h.chunkIndex + 1}] $text\n"
                if (block.length > remaining) {
                    val take = remaining.coerceAtLeast(80)
                    sb.appendLine(block.take(take) + "\n…")
                    remaining = 0
                    break
                } else {
                    sb.appendLine(block)
                    remaining -= block.length
                }
            }
            sb.appendLine()
        }

        return sb.toString().trim()
    }

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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("index_$docId", ExistingWorkPolicy.REPLACE, req)
    }

    private data class DocMeta(val name: String, val mime: String, val sizeBytes: Long)

    private fun queryMeta(uri: Uri): DocMeta {
        val cr = context.contentResolver
        var name = uri.lastPathSegment ?: "document"
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
