package com.nervesparks.iris.rag.storage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class LocalDoc(
    val docId: String,
    val uri: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val status: String, // "INDEXING" | "READY" | "FAILED"
    val error: String? = null
)

data class LocalChunk(
    val chunkId: String,
    val chunkIndex: Int,
    val text: String
)

class LocalRagStore(private val context: Context) {

    companion object {
        private const val TAG = "LocalRagStore"
    }

    private val root: File = File(context.filesDir, "rag")
    private val docsDir: File = File(root, "docs")

    init {
        docsDir.mkdirs()
    }

    fun newDocId(): String = UUID.randomUUID().toString()

    fun docFolder(docId: String): File = File(docsDir, docId)

    fun writeDocMeta(doc: LocalDoc) {
        val dir = docFolder(doc.docId).apply { mkdirs() }
        val metaFile = File(dir, "meta.json")
        atomicWriteText(metaFile, docToJson(doc).toString(2))
    }

    fun readAllDocs(): List<LocalDoc> {
        val out = ArrayList<LocalDoc>()
        val dirs = docsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (d in dirs) {
            val meta = File(d, "meta.json")
            if (!meta.exists()) continue
            runCatching {
                val json = JSONObject(meta.readText())
                out.add(docFromJson(json))
            }.onFailure {
                Log.w(TAG, "readAllDocs: failed for folder=${d.absolutePath}", it)
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun deleteDoc(docId: String) {
        val folder = docFolder(docId)
        if (folder.exists()) {
            val deleted = folder.deleteRecursively()
            Log.d(TAG, "deleteDoc: docId=$docId deleted=$deleted")
        }
    }

    fun deleteAllDocs() {
        Log.i(TAG, "deleteAllDocs: Deleting all RAG data")

        val dirs = docsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        var deletedCount = 0

        for (d in dirs) {
            try {
                if (d.deleteRecursively()) deletedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete folder: ${d.absolutePath}", e)
            }
        }

        Log.i(TAG, "deleteAllDocs: Deleted $deletedCount document folders")
    }

    fun getTotalStorageBytes(): Long = calculateFolderSize(root)

    private fun calculateFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        var size = 0L
        folder.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    fun writeChunksAndEmbeddings(
        docId: String,
        chunks: List<LocalChunk>,
        embeddingsBytes: ByteArray // float32 little-endian, concatenated
    ) {
        val dir = docFolder(docId).apply { mkdirs() }
        val chunksFile = File(dir, "chunks.jsonl")
        val embFile = File(dir, "embeddings.bin")

        // chunks.jsonl
        val sb = StringBuilder()
        for (c in chunks) {
            val jo = JSONObject()
            jo.put("chunkId", c.chunkId)
            jo.put("chunkIndex", c.chunkIndex)
            jo.put("text", c.text)
            sb.append(jo.toString()).append('\n')
        }
        atomicWriteText(chunksFile, sb.toString())

        // embeddings.bin
        atomicWriteBytes(embFile, embeddingsBytes)

        Log.d(TAG, "writeChunksAndEmbeddings: docId=$docId chunks=${chunks.size} embBytes=${embeddingsBytes.size}")
    }

    /**
     * Returns triples (doc, chunk, embeddingBytesForChunk)
     * embeddingBytesForChunk is one chunk vector (dim * 4 bytes)
     */
    fun readAllChunksAndEmbeddings(): List<Triple<LocalDoc, LocalChunk, ByteArray>> {
        val docs = readAllDocs().filter { it.status == "READY" }
        val out = ArrayList<Triple<LocalDoc, LocalChunk, ByteArray>>()

        for (doc in docs) {
            val dir = docFolder(doc.docId)
            val chunksFile = File(dir, "chunks.jsonl")
            val embFile = File(dir, "embeddings.bin")
            if (!chunksFile.exists() || !embFile.exists()) continue

            val lines = chunksFile.readLines().filter { it.isNotBlank() }
            val allEmb = embFile.readBytes()

            val n = lines.size
            if (n == 0) continue
            val bytesPerEmb = allEmb.size / n
            if (bytesPerEmb * n != allEmb.size) continue

            for ((i, line) in lines.withIndex()) {
                val jo = JSONObject(line)
                val chunk = LocalChunk(
                    chunkId = jo.getString("chunkId"),
                    chunkIndex = jo.getInt("chunkIndex"),
                    text = jo.getString("text")
                )
                val start = i * bytesPerEmb
                val emb = allEmb.copyOfRange(start, start + bytesPerEmb)
                out.add(Triple(doc, chunk, emb))
            }
        }

        return out
    }

    /**
     * Read up to [maxChunks] chunks for a specific doc (in file order).
     * Used as fallback when similarity search returns no hits.
     */
    fun readDocChunks(docId: String, maxChunks: Int = 8): List<LocalChunk> {
        if (maxChunks <= 0) return emptyList()
        val dir = docFolder(docId)
        val chunksFile = File(dir, "chunks.jsonl")
        if (!chunksFile.exists()) return emptyList()

        val out = ArrayList<LocalChunk>(maxChunks)
        chunksFile.useLines { seq ->
            seq.filter { it.isNotBlank() }
                .take(maxChunks)
                .forEach { line ->
                    val jo = JSONObject(line)
                    out += LocalChunk(
                        chunkId = jo.getString("chunkId"),
                        chunkIndex = jo.getInt("chunkIndex"),
                        text = jo.getString("text")
                    )
                }
        }
        return out
    }

    fun readAllDocChunks(docId: String): List<LocalChunk> {
        val dir = docFolder(docId)
        val chunksFile = File(dir, "chunks.jsonl")
        if (!chunksFile.exists()) return emptyList()

        val out = ArrayList<LocalChunk>()
        chunksFile.useLines { seq ->
            seq.filter { it.isNotBlank() }
                .forEach { line ->
                    val jo = JSONObject(line)
                    out += LocalChunk(
                        chunkId = jo.getString("chunkId"),
                        chunkIndex = jo.getInt("chunkIndex"),
                        text = jo.getString("text")
                    )
                }
        }
        return out.sortedBy { it.chunkIndex }
    }

    /**
     * ✅ NEW: Read embeddings.bin bytes for a doc (raw concatenated float32 LE).
     * Helpful for diagnostics or future pre-warm.
     */
    fun readDocEmbeddingsBytes(docId: String): ByteArray? {
        val dir = docFolder(docId)
        val embFile = File(dir, "embeddings.bin")
        if (!embFile.exists()) return null
        return runCatching { embFile.readBytes() }.getOrNull()
    }

    /**
     * ✅ NEW: infer embedding dimension (float count) from embeddings.bin and chunk count.
     */
    fun readDocEmbeddingsDim(docId: String): Int? {
        val dir = docFolder(docId)
        val chunksFile = File(dir, "chunks.jsonl")
        val embFile = File(dir, "embeddings.bin")
        if (!chunksFile.exists() || !embFile.exists()) return null

        val chunkCount = chunksFile.useLines { it.count { line -> line.isNotBlank() } }
        if (chunkCount <= 0) return null

        val bytes = embFile.length()
        val bytesPer = bytes / chunkCount
        if (bytesPer * chunkCount != bytes) return null
        return (bytesPer / 4L).toInt() // float32
    }

    fun getDocStats(docId: String): DocStats? {
        val dir = docFolder(docId)
        val chunksFile = File(dir, "chunks.jsonl")
        val embFile = File(dir, "embeddings.bin")
        val metaFile = File(dir, "meta.json")

        if (!metaFile.exists()) return null

        val chunkCount = if (chunksFile.exists()) {
            chunksFile.useLines { it.count { line -> line.isNotBlank() } }
        } else 0

        val embSize = if (embFile.exists()) embFile.length() else 0L
        val totalSize = calculateFolderSize(dir)

        return DocStats(
            docId = docId,
            chunkCount = chunkCount,
            embeddingBytes = embSize,
            totalBytes = totalSize
        )
    }

    data class DocStats(
        val docId: String,
        val chunkCount: Int,
        val embeddingBytes: Long,
        val totalBytes: Long
    )

    // ---------- helpers ----------

    private fun docToJson(d: LocalDoc): JSONObject = JSONObject().apply {
        put("docId", d.docId)
        put("uri", d.uri)
        put("name", d.name)
        put("mime", d.mime)
        put("sizeBytes", d.sizeBytes)
        put("createdAt", d.createdAt)
        put("status", d.status)
        if (d.error != null) put("error", d.error) else put("error", JSONObject.NULL)
    }

    private fun docFromJson(j: JSONObject): LocalDoc {
        val errorValue: String? = if (j.isNull("error")) null else j.optString("error", "")
        return LocalDoc(
            docId = j.getString("docId"),
            uri = j.getString("uri"),
            name = j.getString("name"),
            mime = j.getString("mime"),
            sizeBytes = j.optLong("sizeBytes", 0L),
            createdAt = j.optLong("createdAt", 0L),
            status = j.optString("status", "READY"),
            error = errorValue
        )
    }

    /**
     * Safer atomic write: temp file then replace.
     * renameTo can fail across FS boundaries, so we fallback to copy.
     */
    private fun atomicWriteText(file: File, text: String) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        replaceFile(tmp, file)
    }

    private fun atomicWriteBytes(file: File, bytes: ByteArray) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        replaceFile(tmp, file)
    }

    private fun replaceFile(tmp: File, dest: File) {
        if (dest.exists()) dest.delete()

        val renamed = tmp.renameTo(dest)
        if (renamed) return

        // Fallback copy if rename fails
        runCatching {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }.onFailure {
            Log.w(TAG, "replaceFile failed dest=${dest.absolutePath}", it)
        }
    }
}
