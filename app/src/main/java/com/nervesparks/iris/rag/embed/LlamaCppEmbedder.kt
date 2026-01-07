package com.nervesparks.iris.rag.embed

import android.llama.cpp.LLamaAndroid
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt

class LlamaCppEmbedder(
    private val modelPath: String,
    private val userThreads: Int = 4,
    private val nCtx: Int = 512,
    private val poolingType: Int = 1, // 1=MEAN, 2=CLS, 3=LAST (must be supported natively)
    private val normalize: Boolean = true
) : Embedder {

    private val llama = LLamaAndroid.instance()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            // Must NOT load on main thread (native load is heavy)
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "LlamaCppEmbedder.ensureLoaded() called on main thread. " +
                        "Load/embed must run on Dispatchers.IO/Default/Worker."
            }

            runBlocking {
                try {
                    // Your LLamaAndroid must implement this
                    llama.loadEmbeddingModel(
                        pathToModel = modelPath,
                        userThreads = userThreads,
                        nCtx = nCtx,
                        poolingType = poolingType
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to load embedding model at: $modelPath", t)
                    throw IllegalStateException(
                        "Failed to load embedding model. " +
                                "Check file exists + is readable + correct GGUF embedding model.\n" +
                                "Path: $modelPath\n" +
                                "Cause: ${t.message}",
                        t
                    )
                }
            }

            loaded = true
        }
    }

    override fun embed(text: String): FloatArray {
        // Avoid blocking UI thread (runBlocking + native)
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "embed() called on main thread. Call from a background dispatcher (Default/IO/Worker)."
        }

        val q = text.trim()
        if (q.isEmpty()) return FloatArray(0)

        ensureLoaded()

        val vec = runBlocking {
            try {
                llama.embed(q)
            } catch (t: Throwable) {
                Log.e(TAG, "embed() native call failed", t)
                throw IllegalStateException("Embedding failed: ${t.message}", t)
            }
        }

        if (!normalize || vec.isEmpty()) return vec
        return l2NormalizeInPlace(vec)
    }

    private fun l2NormalizeInPlace(x: FloatArray): FloatArray {
        var sum = 0.0
        for (v in x) sum += (v * v).toDouble()
        val denom = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        for (i in x.indices) x[i] /= denom
        return x
    }

    companion object {
        private const val TAG = "LlamaCppEmbedder"
    }
}
