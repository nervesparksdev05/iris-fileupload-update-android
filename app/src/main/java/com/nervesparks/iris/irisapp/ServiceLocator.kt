package com.nervesparks.iris.irisapp

import android.content.Context
import android.util.Log
import com.nervesparks.iris.rag.RagRepository
import com.nervesparks.iris.rag.embed.Embedder
import com.nervesparks.iris.rag.embed.LlamaCppEmbedder
import com.nervesparks.iris.rag.storage.LocalRagStore
import java.io.File
import java.io.FileOutputStream

object ServiceLocator {
    private const val TAG = "ServiceLocator"

    // ✅ Embedding GGUF shipped with the app / copied into app storage (offline)
    private const val EMBED_MODEL_FILE = "bge-small-en-v1.5-q4_k_m.gguf"

    @Volatile private var initialized = false

    lateinit var embedder: Embedder
        private set

    lateinit var localRagStore: LocalRagStore
        private set

    lateinit var ragRepository: RagRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val appCtx = context.applicationContext

            // ✅ file-based local storage (fully offline)
            localRagStore = LocalRagStore(appCtx)

            // ✅ resolve embedding model path (no downloads)
            val embedModelFile = resolveOrCopyEmbedModel(appCtx)

            embedder = LlamaCppEmbedder(
                modelPath = embedModelFile.absolutePath,
                userThreads = 4,
                nCtx = 512,
                poolingType = 1,  // 1=MEAN
                normalize = true
            )

            // ✅ repository uses local store + embedder
            ragRepository = RagRepository(appCtx, localRagStore, embedder)

            initialized = true
            Log.i(TAG, "Initialized. Embed model=${embedModelFile.absolutePath} (${embedModelFile.length()} bytes)")
        }
    }

    /**
     * Look for the embedding model in:
     * 1) internal filesDir
     * 2) externalFilesDir (app-scoped) (if available)
     * 3) assets root (copied into filesDir)
     */
    private fun resolveOrCopyEmbedModel(context: Context): File {
        val internal = File(context.filesDir, EMBED_MODEL_FILE)

        val externalDir = context.getExternalFilesDir(null)
        val external = externalDir?.let { File(it, EMBED_MODEL_FILE) }

        // Prefer internal if present
        if (internal.exists() && internal.length() > 0L) return internal

        // If exists externally, copy into internal for stability
        if (external != null && external.exists() && external.length() > 0L) {
            external.copyTo(internal, overwrite = true)
            return internal
        }

        // Try to copy from assets (if you added the GGUF under app/src/main/assets/)
        return tryCopyAsset(context, EMBED_MODEL_FILE, internal)
    }

    @Throws(IllegalStateException::class)
    private fun tryCopyAsset(context: Context, assetName: String, outFile: File): File {
        if (outFile.exists() && outFile.length() > 0L) return outFile

        val externalPath = context.getExternalFilesDir(null)
            ?.let { File(it, assetName).absolutePath }

        return try {
            context.assets.open(assetName).use { input ->
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }

            if (!outFile.exists() || outFile.length() <= 0L) {
                throw IllegalStateException("Asset copy produced empty file: ${outFile.absolutePath}")
            }
            outFile
        } catch (t: Throwable) {
            val msg = """
                Embedding model not found.
                Place $assetName at one of:
                - ${outFile.absolutePath}
                - ${externalPath ?: "(externalFilesDir unavailable on this device)"}
                - or package it in: app/src/main/assets/$assetName
                Cause: ${t.message}
            """.trimIndent()

            throw IllegalStateException(msg, t)
        }
    }
}
