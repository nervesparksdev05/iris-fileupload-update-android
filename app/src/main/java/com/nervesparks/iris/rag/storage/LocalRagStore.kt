package com.nervesparks.iris.rag.storage

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        atomicWrite(metaFile, docToJson(doc).toString(2))
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
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun deleteDoc(docId: String) {
        docFolder(docId).deleteRecursively()
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
        atomicWrite(chunksFile, sb.toString())

        // embeddings.bin
        atomicWriteBytes(embFile, embeddingsBytes)
    }

    fun readAllChunksAndEmbeddings(): List<Triple<LocalDoc, LocalChunk, ByteArray>> {
        // Returns triples (doc, chunk, embeddingBytesForChunk)
        // embeddingBytesForChunk is one chunk vector (dim * 4 bytes)
        val docs = readAllDocs().filter { it.status == "READY" }
        val out = ArrayList<Triple<LocalDoc, LocalChunk, ByteArray>>()

        for (doc in docs) {
            val dir = docFolder(doc.docId)
            val chunksFile = File(dir, "chunks.jsonl")
            val embFile = File(dir, "embeddings.bin")
            if (!chunksFile.exists() || !embFile.exists()) continue

            val lines = chunksFile.readLines().filter { it.isNotBlank() }
            val allEmb = embFile.readBytes()

            // We assume embeddings are stored in the same order as chunks.jsonl
            // and each embedding is fixed-size. We infer size from total/lines count.
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

    private fun docFromJson(j: JSONObject): LocalDoc =
        LocalDoc(
            docId = j.getString("docId"),
            uri = j.getString("uri"),
            name = j.getString("name"),
            mime = j.getString("mime"),
            sizeBytes = j.optLong("sizeBytes", 0L),
            createdAt = j.optLong("createdAt", 0L),
            status = j.optString("status", "READY"),
            error = if (j.isNull("error")) null else j.optString("error", null)
        )

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun atomicWriteBytes(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}
