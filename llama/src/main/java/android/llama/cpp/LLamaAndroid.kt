package android.llama.cpp

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Production notes:
 * - Chat (LLM) model + embedding model are managed independently.
 * - Everything runs on a dedicated single-thread runLoop that owns llama.cpp native state.
 * - Fully offline.
 *
 * IMPORTANT RAG NOTE:
 * - If n_ctx=1024 and you set n_len too high (e.g., 768), llama.cpp will trim the prompt heavily
 *   (max_prompt ~ 248 tokens), which effectively drops your document context.
 * - Keep n_len ~ 128..256 for RAG to work with n_ctx=1024.
 */
class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    @Volatile private var stopGeneration: Boolean = false

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    @Volatile private var embeddingState: EmbState = EmbState.Idle

    private val _isSending = mutableStateOf(false)
    private val isSending: Boolean by _isSending

    private val _isMarked = mutableStateOf(false)
    private val isMarked: Boolean by _isMarked

    private val _isCompleteEOT = mutableStateOf(true)
    private val isCompleteEOT: Boolean by _isCompleteEOT

    // avoid JVM clash by NOT creating a delegated property named endedByLimit
    private val _endedByLimitState = mutableStateOf(false)

    fun getIsSending(): Boolean = isSending
    fun getIsMarked(): Boolean = isMarked
    fun getIsCompleteEOT(): Boolean = isCompleteEOT
    fun getEndedByLimit(): Boolean = _endedByLimitState.value

    fun stopTextGeneration() {
        stopGeneration = true
        _isSending.value = false
        _endedByLimitState.value = false
        _isMarked.value = false
    }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            System.loadLibrary("llama-android")

            log_to_android()
            backend_init()

            Log.d(tag, system_info())
            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception", exception)
            }
        }
    }.asCoroutineDispatcher()

    // ✅ DEFAULT FIX FOR RAG: keep max new tokens lower so prompt has room.
    // With n_ctx=1024, nlen=256 leaves ~768 tokens for prompt/context.
    @Volatile private var nlenDefault: Int = 256

    // Must match native n_ctx (=1024) unless you also change native new_context() to accept n_ctx.
    private val context_size: Int = 1024

    // llama_batch allocation capacity.
    // This is allocation capacity; completion_init() still trims prompt to fit n_ctx.
    private val chat_batch_tokens: Int = 1024

    fun setDefaultMaxNewTokens(n: Int) {
        // Keep sane bounds; too high breaks RAG by trimming prompt.
        nlenDefault = n.coerceIn(64, 512)
        Log.i(tag, "setDefaultMaxNewTokens: nlenDefault=$nlenDefault (context_size=$context_size)")
    }

    // ---------------- Native bindings (chat) ----------------
    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long, userThreads: Int): Long
    private external fun free_context(context: Long)
    private external fun backend_init()
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(top_p: Float, top_k: Int, temp: Float): Long
    private external fun free_sampler(sampler: Long)

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        nLen: Int
    ): Int

    private external fun oaicompat_completion_param_parse(
        allmessages: Array<Map<String, String>>,
        model: Long
    ): String

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)
    private external fun get_eot_str(model: Long): String

    // ---------------- Native bindings (embeddings) ----------------
    private external fun new_embedding_context(model: Long, userThreads: Int, nCtx: Int, poolingType: Int): Long
    private external fun embedding_for_text(context: Long, batch: Long, text: String): FloatArray

    // ---------------- Chat API ----------------

    suspend fun load(pathToModel: String, userThreads: Int, topK: Int, topP: Float, temp: Float) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed")

                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                    val threads = userThreads.coerceIn(2, minOf(8, cores))

                    val context = new_context(model, threads)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(chat_batch_tokens, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler(top_k = topK, top_p = topP, temp = temp)
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    val modelEotStr = get_eot_str(model)
                    if (modelEotStr.isBlank()) throw IllegalStateException("get_eot_str() failed")

                    Log.i(
                        tag,
                        "Loaded chat model=$pathToModel threads=$threads batchTokens=$chat_batch_tokens " +
                                "context_size=$context_size nlenDefault=$nlenDefault"
                    )
                    threadLocalState.set(State.Loaded(model, context, batch, sampler, modelEotStr))
                }

                else -> throw IllegalStateException("Chat model already loaded")
            }
        }
    }

    suspend fun getTemplate(messages: List<Map<String, String>>): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> oaicompat_completion_param_parse(messages.toTypedArray(), state.model)
                else -> ""
            }
        }
    }

    /**
     * Send a fully formatted prompt (already templated).
     *
     * maxNewTokens:
     * - If null, uses nlenDefault (recommended 128..256 for RAG with n_ctx=1024).
     */
    suspend fun send(message: String, maxNewTokens: Int? = null): Flow<String> = flow {
        stopGeneration = false
        _isSending.value = true
        _isCompleteEOT.value = true
        _endedByLimitState.value = false
        _isMarked.value = false

        val nlenEffective = (maxNewTokens ?: nlenDefault).coerceIn(64, 512)

        try {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    val ncur = IntVar(completion_init(state.context, state.batch, message, nlenEffective))

                    var endTokenStore = ""
                    var producedTokens = 0

                    while (!stopGeneration && producedTokens < nlenEffective && ncur.getValue() < context_size) {
                        val str = completion_loop(state.context, state.batch, state.sampler, nlenEffective, ncur)
                        producedTokens += 1

                        if (str == "```" || str == "``") {
                            _isMarked.value = !_isMarked.value
                        }

                        if (str == null) {
                            _isCompleteEOT.value = true
                            break
                        }

                        endTokenStore += str

                        // stop on EOT
                        if (endTokenStore.length > state.modelEotStr.length && endTokenStore.contains(state.modelEotStr)) {
                            _isCompleteEOT.value = false
                            break
                        }

                        // keep store bounded
                        if ((endTokenStore.length / 2) > state.modelEotStr.length) {
                            endTokenStore = endTokenStore.slice((endTokenStore.length / 2) until endTokenStore.length)
                        }

                        emit(str)
                    }

                    // If we didn't hit EOT and weren't manually stopped, we likely hit a token/context limit.
                    if (!stopGeneration && _isCompleteEOT.value &&
                        (producedTokens >= nlenEffective || ncur.getValue() >= context_size - 1)
                    ) {
                        _endedByLimitState.value = true
                    }

                    kv_cache_clear(state.context)
                }

                else -> {
                    // Not loaded
                }
            }
        } finally {
            _isSending.value = false
        }
    }.flowOn(runLoop)

    suspend fun myCustomBenchmark(): Flow<String> = flow {
        try {
            withTimeout(30.seconds) {
                when (val state = threadLocalState.get()) {
                    is State.Loaded -> {
                        val nlenEffective = nlenDefault.coerceIn(64, 512)
                        val ncur = IntVar(
                            completion_init(
                                state.context,
                                state.batch,
                                "Write an article on global warming in 1000 words",
                                nlenEffective
                            )
                        )
                        var produced = 0
                        while (!stopGeneration && produced < nlenEffective) {
                            val str = completion_loop(state.context, state.batch, state.sampler, nlenEffective, ncur)
                            produced++
                            if (str == null) {
                                _isCompleteEOT.value = true
                                break
                            }
                            emit(str)
                        }
                        kv_cache_clear(state.context)
                    }

                    else -> {}
                }
            }
        } finally {
            _isSending.value = false
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_sampler(state.sampler)
                    free_batch(state.batch)
                    free_context(state.context)
                    free_model(state.model)
                    threadLocalState.set(State.Idle)
                }
                else -> {}
            }
        }
    }

    // ---------------- Embeddings API ----------------

    suspend fun loadEmbeddingModel(pathToModel: String, userThreads: Int, nCtx: Int, poolingType: Int) {
        withContext(runLoop) {
            when (embeddingState) {
                is EmbState.Loaded -> return@withContext
                EmbState.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed for embedding model")

                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                    val threads = userThreads.coerceIn(2, minOf(8, cores))

                    val context = new_embedding_context(model, threads, nCtx, poolingType)
                    if (context == 0L) {
                        free_model(model)
                        throw IllegalStateException("new_embedding_context() failed")
                    }

                    val batch = new_batch(nCtx, 0, 1)
                    if (batch == 0L) {
                        free_context(context)
                        free_model(model)
                        throw IllegalStateException("new_batch() failed for embedding model")
                    }

                    Log.i(tag, "Loaded embedding model=$pathToModel threads=$threads nCtx=$nCtx pooling=$poolingType")
                    embeddingState = EmbState.Loaded(model = model, context = context, batch = batch)
                }
            }
        }
    }

    suspend fun embed(text: String): FloatArray {
        return withContext(runLoop) {
            when (val s = embeddingState) {
                is EmbState.Loaded -> embedding_for_text(s.context, s.batch, text)
                EmbState.Idle -> throw IllegalStateException("Embedding model not loaded. Call loadEmbeddingModel() first.")
            }
        }
    }

    suspend fun unloadEmbeddingModel() {
        withContext(runLoop) {
            when (val s = embeddingState) {
                is EmbState.Loaded -> {
                    free_batch(s.batch)
                    free_context(s.context)
                    free_model(s.model)
                    embeddingState = EmbState.Idle
                }
                EmbState.Idle -> {}
            }
        }
    }

    fun send_eot_str(): String {
        return when (val state = threadLocalState.get()) {
            is State.Loaded -> state.modelEotStr
            else -> "<|im_end|>"
        }
    }

    companion object {
        /**
         * JNI expects:
         * - getValue():I
         * - inc():V
         *
         * IMPORTANT: No Kotlin property called "value" (it auto-generates getValue()).
         */
        private class IntVar(initial: Int) {
            @Volatile private var v: Int = initial
            fun getValue(): Int = v
            fun inc() { synchronized(this) { v += 1 } }
        }

        private sealed interface State {
            data object Idle : State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long,
                val modelEotStr: String
            ) : State
        }

        private sealed interface EmbState {
            data object Idle : EmbState
            data class Loaded(val model: Long, val context: Long, val batch: Long) : EmbState
        }

        private val _instance: LLamaAndroid = LLamaAndroid()
        fun instance(): LLamaAndroid = _instance
    }
}
