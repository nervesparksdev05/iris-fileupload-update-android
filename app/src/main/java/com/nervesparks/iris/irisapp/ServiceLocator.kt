package com.nervesparks.iris.irisapp

import android.content.Context
import android.util.Log
import com.nervesparks.iris.rag.RagRepository
import com.nervesparks.iris.rag.embed.Embedder
import com.nervesparks.iris.rag.embed.LlamaCppEmbedder
import com.nervesparks.iris.rag.storage.LocalRagStore
import java.io.File
import kotlin.math.max
import kotlin.math.min

object ServiceLocator {
    private const val TAG = "ServiceLocator"

    private const val EMBED_MODEL_FILE = "bge-small-en-v1.5-q4_k_m.gguf"
    private const val EMBED_MODEL_URL =
        "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf"

    @Volatile private var initialized = false

    private val embedderProxy = ProxyEmbedder()

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

            localRagStore = LocalRagStore(appCtx)
            embedder = embedderProxy
            ragRepository = RagRepository(appCtx, localRagStore, embedder)

            // If already present, attach now (no crash if missing)
            ensureEmbeddingReady(appCtx)

            initialized = true
            Log.i(TAG, "Initialized. embedReady=${embedderProxy.isReady()}")
        }
    }

    fun getEmbeddingModelDownloadInfo(): Pair<String, String> =
        EMBED_MODEL_FILE to EMBED_MODEL_URL

    /**
     * Single place to resolve the embedding model file if available.
     * Preference: internal filesDir, else externalFilesDir.
     */
    fun getEmbeddingModelFile(context: Context): File? {
        val internal = File(context.filesDir, EMBED_MODEL_FILE)
        if (internal.exists() && internal.length() > 0L) return internal

        val external = context.getExternalFilesDir(null)?.let { File(it, EMBED_MODEL_FILE) }
        if (external != null && external.exists() && external.length() > 0L) return external

        return null
    }

    fun isEmbeddingModelDownloaded(context: Context): Boolean {
        return getEmbeddingModelFile(context) != null
    }

    /**
     * Called after download completes (and safe to call any time).
     * Returns true if embedding is ready after this call.
     */
    fun ensureEmbeddingReady(context: Context): Boolean {
        if (embedderProxy.isReady()) return true

        val internal = File(context.filesDir, EMBED_MODEL_FILE)
        val external = context.getExternalFilesDir(null)?.let { File(it, EMBED_MODEL_FILE) }

        val modelFile: File? = when {
            internal.exists() && internal.length() > 0L -> internal

            external != null && external.exists() && external.length() > 0L -> {
                // Try to copy external -> internal so future reads are fast + stable
                runCatching { external.copyTo(internal, overwrite = true) }
                    .onFailure { Log.w(TAG, "copy external→internal failed", it) }

                if (internal.exists() && internal.length() > 0L) internal else external
            }

            else -> null
        }

        if (modelFile == null || !modelFile.exists() || modelFile.length() <= 0L) {
            Log.w(TAG, "Embedding model not found yet; RAG disabled until downloaded.")
            return false
        }

        val cpu = Runtime.getRuntime().availableProcessors()
        val threads = min(4, max(2, cpu / 2)) // sensible default for mobile

        val real = LlamaCppEmbedder(
            modelPath = modelFile.absolutePath,
            userThreads = threads,
            nCtx = 512,
            poolingType = 1,
            normalize = true
        )

        embedderProxy.attach(real)

        Log.i(TAG, "Embedding ready: ${modelFile.absolutePath} (${modelFile.length()} bytes) threads=$threads")
        return true
    }

    /**
     * Handy when:
     * - user deletes/re-adds docs
     * - embedding model changes
     * - low-memory cleanup
     */
    fun clearRagCache() {
        if (!initialized) return
        runCatching { ragRepository.clearCache() }
            .onFailure { Log.w(TAG, "clearRagCache failed", it) }
    }

    private class ProxyEmbedder : Embedder {
        @Volatile private var delegate: Embedder? = null

        fun attach(real: Embedder) { delegate = real }
        fun isReady(): Boolean = delegate != null

        override fun embed(text: String): FloatArray {
            val d = delegate ?: throw IllegalStateException(
                "Embedding model not downloaded. Go to Settings → Models and download it."
            )
            return d.embed(text)
        }
    }
}
