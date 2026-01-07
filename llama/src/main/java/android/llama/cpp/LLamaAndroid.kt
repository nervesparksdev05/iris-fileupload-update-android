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
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Production notes:
 * - Chat (LLM) model + embedding model are managed independently.
 * - Everything runs on a dedicated single-thread runLoop that owns llama.cpp native state.
 * - Fully offline: no network calls, no remote APIs.
 */
class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    private var stopGeneration: Boolean = false

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    @Volatile
    private var embeddingState: EmbState = EmbState.Idle

    private val _isSending = mutableStateOf(false)
    private val isSending: Boolean by _isSending

    private val _isMarked = mutableStateOf(false)
    private val isMarked: Boolean by _isMarked

    private val _isCompleteEOT = mutableStateOf(true)
    private val isCompleteEOT: Boolean by _isCompleteEOT

    fun getIsSending(): Boolean = isSending
    fun getIsMarked(): Boolean = isMarked
    fun getIsCompleteEOT(): Boolean = isCompleteEOT

    fun stopTextGeneration() {
        _isSending.value = false
        stopGeneration = true
        _isMarked.value = false
    }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            // No-op if called more than once.
            System.loadLibrary("llama-android")

            // Route llama.cpp logs into logcat + init backend.
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

    private val nlen: Int = 1024
    private val context_size: Int = 4096

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
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

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

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }
                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String, userThreads: Int, topK: Int, topP: Float, temp: Float) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed")

                    val context = new_context(model, userThreads)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(4096, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler(top_k = topK, top_p = topP, temp = temp)
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    val modelEotStr = get_eot_str(model)
                    if (modelEotStr.isBlank()) throw IllegalStateException("get_eot_str() failed")

                    Log.i(tag, "Loaded chat model $pathToModel")
                    threadLocalState.set(State.Loaded(model, context, batch, sampler, modelEotStr))
                }
                else -> throw IllegalStateException("Chat model already loaded")
            }
        }
    }

    suspend fun getTemplate(messages: List<Map<String, String>>): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    val arrayMessages = messages.toTypedArray()
                    oaicompat_completion_param_parse(allmessages = arrayMessages, model = state.model)
                }
                else -> ""
            }
        }
    }

    suspend fun send(message: String): Flow<String> = flow {
        stopGeneration = false
        _isSending.value = true

        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                val ncur = IntVar(completion_init(state.context, state.batch, message, nlen))
                var endTokenStore = ""
                var chatLen = 0

                while (chatLen <= nlen && ncur.value < context_size && !stopGeneration) {
                    _isSending.value = true
                    val str = completion_loop(state.context, state.batch, state.sampler, nlen, ncur)
                    chatLen += 1

                    if (str == "```" || str == "``") {
                        _isMarked.value = !_isMarked.value
                    }

                    if (str == null) {
                        _isSending.value = false
                        _isCompleteEOT.value = true
                        break
                    }

                    endTokenStore += str

                    // EOT handling (model-dependent)
                    if (endTokenStore.length > state.modelEotStr.length && endTokenStore.contains(state.modelEotStr)) {
                        _isSending.value = false
                        _isCompleteEOT.value = false
                        break
                    }
                    if ((endTokenStore.length / 2) > state.modelEotStr.length) {
                        endTokenStore = endTokenStore.slice((endTokenStore.length / 2) until endTokenStore.length)
                    }

                    if (stopGeneration) break
                    emit(str)
                }

                kv_cache_clear(state.context)
            }
            else -> _isSending.value = false
        }

        _isSending.value = false
    }.flowOn(runLoop)

    suspend fun myCustomBenchmark(): Flow<String> = flow {
        try {
            withTimeout(30.seconds) {
                when (val state = threadLocalState.get()) {
                    is State.Loaded -> {
                        val ncur = IntVar(
                            completion_init(
                                state.context,
                                state.batch,
                                "Write an article on global warming in 1000 words",
                                nlen
                            )
                        )
                        while (ncur.value <= nlen) {
                            val str = completion_loop(state.context, state.batch, state.sampler, nlen, ncur)
                            if (str == null) {
                                _isSending.value = false
                                _isCompleteEOT.value = true
                                break
                            }
                            if (stopGeneration) break
                            emit(str)
                        }
                        kv_cache_clear(state.context)
                    }
                    else -> _isSending.value = false
                }
            }
        } finally {
            _isSending.value = false
        }
    }.flowOn(runLoop)

    /**
     * Unloads ONLY the chat model (keeps embeddings model loaded for RAG).
     */
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

    /**
     * Loads the embeddings GGUF model (BGE, etc.) into a dedicated llama context.
     * Safe to call multiple times; it's a no-op when already loaded.
     */
    suspend fun loadEmbeddingModel(
        pathToModel: String,
        userThreads: Int,
        nCtx: Int,
        poolingType: Int
    ) {
        withContext(runLoop) {
            when (embeddingState) {
                is EmbState.Loaded -> return@withContext
                EmbState.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed for embedding model")

                    val context = new_embedding_context(model, userThreads, nCtx, poolingType)
                    if (context == 0L) {
                        free_model(model)
                        throw IllegalStateException("new_embedding_context() failed")
                    }

                    // batch sized to nCtx (we cap tokens n_ctx in native for safety)
                    val batch = new_batch(nCtx, 0, 1)
                    if (batch == 0L) {
                        free_context(context)
                        free_model(model)
                        throw IllegalStateException("new_batch() failed for embedding model")
                    }

                    Log.i(tag, "Loaded embedding model $pathToModel")
                    embeddingState = EmbState.Loaded(model = model, context = context, batch = batch)
                }
            }
        }
    }

    /**
     * Compute a single text embedding. Requires embedding model loaded.
     * This always runs on runLoop (native thread).
     */
    suspend fun embed(text: String): FloatArray {
        return withContext(runLoop) {
            when (val s = embeddingState) {
                is EmbState.Loaded -> embedding_for_text(s.context, s.batch, text)
                EmbState.Idle -> throw IllegalStateException("Embedding model not loaded. Call loadEmbeddingModel() first.")
            }
        }
    }

    /**
     * Unloads the embeddings model. Call only when you are done with RAG.
     */
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
        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) { value += 1 }
            }
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

        // Enforce only one instance.
        private val _instance: LLamaAndroid = LLamaAndroid()
        fun instance(): LLamaAndroid = _instance
    }
}
