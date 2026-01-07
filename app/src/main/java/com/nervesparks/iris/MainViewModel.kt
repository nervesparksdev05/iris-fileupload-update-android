package com.nervesparks.iris

import android.content.Context
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervesparks.iris.data.UserPreferencesRepository
import com.nervesparks.iris.docs.DocumentUriPermission
import com.nervesparks.iris.irisapp.ServiceLocator
import com.nervesparks.iris.rag.RagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class MainViewModel(
    private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance(),
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val ragRepo: RagRepository = ServiceLocator.ragRepository

    private val _defaultModelName = mutableStateOf("")
    val defaultModelName: State<String> = _defaultModelName

    // -----------------------------
    // 📎 User documents (Production RAG)
    // -----------------------------
    val indexedDocs = ragRepo.observeDocs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearUserDocs() {
        viewModelScope.launch(Dispatchers.IO) {
            indexedDocs.value.forEach { ragRepo.removeDocument(it.docId) }
        }
    }

    fun removeUserDoc(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ragRepo.removeDocument(docId)
        }
    }

    /**
     * ✅ Updated behavior (FIX):
     * - Persist URI permission (best-effort)
     * - Copy picked docs into internal storage -> stable file:// URIs
     * - Add those stable URIs to RAG pipeline (Room + Worker)
     *
     * Why: WorkManager runs later/in another process; content:// URIs often fail then.
     */
    fun addUserDocs(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return

        // 1) Best-effort persist permission (for content://)
        uris.forEach { uri ->
            try {
                DocumentUriPermission.persistReadPermission(context, uri)
            } catch (t: Throwable) {
                Log.w(TAG, "persistReadPermission failed uri=$uri", t)
            }
        }

        // 2) Copy into app-internal storage so Worker can always read it later (file://).
        //    This keeps the app fully offline + stable across reboots.
        viewModelScope.launch(Dispatchers.IO) {
            val stableUris = ArrayList<Uri>(uris.size)

            for (uri in uris) {
                runCatching { copyUriIntoAppStorage(context, uri) }
                    .onSuccess { stableUris += it }
                    .onFailure { t -> Log.e(TAG, "Failed to copy uri=$uri into app storage", t) }
            }

            if (stableUris.isNotEmpty()) {
                ragRepo.addDocuments(stableUris)
            }
        }
    }

    private fun copyUriIntoAppStorage(context: Context, uri: Uri): Uri {
        val resolver = context.contentResolver

        val meta = runCatching {
            com.nervesparks.iris.docs.DocumentTextExtractor.queryMeta(resolver, uri)
        }.getOrNull()

        val displayName = meta?.displayName ?: (uri.lastPathSegment ?: "document")
        val safeName = sanitizeFileName(displayName)

        // Safety cap for local copy (tune as needed)
        val maxBytes = 100L * 1024L * 1024L // 100 MB
        if ((meta?.sizeBytes ?: 0L) > maxBytes) {
            throw IllegalStateException("File too large for offline indexing: ${meta?.sizeBytes} bytes")
        }

        val docsDir = File(context.filesDir, "user_docs").apply { mkdirs() }
        val outFile = File(docsDir, "${System.currentTimeMillis()}_${UUID.randomUUID()}_$safeName")

        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                val buf = ByteArray(1024 * 256)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total > maxBytes) {
                        throw IllegalStateException("File exceeded maxBytes while copying ($maxBytes).")
                    }
                }
                output.flush()
            }
        } ?: throw IllegalStateException("Unable to open input stream for uri=$uri")

        return Uri.fromFile(outFile)
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "document" }
        val cleaned = trimmed.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        return cleaned.take(120)
    }

    /**
     * ✅ Merge docContext into first system message
     */
    private fun withDocContext(
        msgs: List<Map<String, String>>,
        docContext: String
    ): List<Map<String, String>> {
        val idx = msgs.indexOfFirst { it["role"] == "system" }
        return if (idx >= 0) {
            val old = msgs[idx]["content"].orEmpty()
            val merged = old + "\n\n" + docContext
            msgs.toMutableList().apply {
                set(idx, msgs[idx] + ("content" to merged))
            }
        } else {
            listOf(mapOf("role" to "system", "content" to docContext)) + msgs
        }
    }

    init {
        loadDefaultModelName()
    }

    private fun loadDefaultModelName() {
        _defaultModelName.value = userPreferencesRepository.getDefaultModelName()
    }

    fun setDefaultModelName(modelName: String) {
        userPreferencesRepository.setDefaultModelName(modelName)
        _defaultModelName.value = modelName
    }

    lateinit var selectedModel: String

    var messages by mutableStateOf(listOf<Map<String, String>>())
        private set

    var newShowModal by mutableStateOf(false)
    var showDownloadInfoModal by mutableStateOf(false)
    var user_thread by mutableStateOf(0f)
    var topP by mutableStateOf(0f)
    var topK by mutableStateOf(0)
    var temp by mutableStateOf(0f)

    var allModels by mutableStateOf(
        listOf(
            mapOf(
                "name" to "Llama-3.2-1B-Instruct-Q6_K_L.gguf",
                "source" to "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K_L.gguf?download=true",
                "destination" to "Llama-3.2-1B-Instruct-Q6_K_L.gguf"
            ),
            mapOf(
                "name" to "Llama-3.2-3B-Instruct-Q4_K_L.gguf",
                "source" to "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_L.gguf?download=true",
                "destination" to "Llama-3.2-3B-Instruct-Q4_K_L.gguf"
            ),
            mapOf(
                "name" to "stablelm-2-1_6b-chat.Q4_K_M.imx.gguf",
                "source" to "https://huggingface.co/Crataco/stablelm-2-1_6b-chat-imatrix-GGUF/resolve/main/stablelm-2-1_6b-chat.Q4_K_M.imx.gguf?download=true",
                "destination" to "stablelm-2-1_6b-chat.Q4_K_M.imx.gguf"
            )
        )
    )

    private var first by mutableStateOf(true)
    var userSpecifiedThreads by mutableIntStateOf(2)

    var message by mutableStateOf("")
        private set

    var userGivenModel by mutableStateOf("")
    var SearchedName by mutableStateOf("")

    private var textToSpeech: TextToSpeech? = null
    var textForTextToSpeech = ""
    var stateForTextToSpeech by mutableStateOf(true)
        private set

    var eot_str = ""
    var refresh by mutableStateOf(false)

    fun loadExistingModels(directory: File) {
        directory.listFiles { file -> file.extension == "gguf" }?.forEach { file ->
            val modelName = file.name
            if (!allModels.any { it["name"] == modelName }) {
                allModels += mapOf(
                    "name" to modelName,
                    "source" to "local",
                    "destination" to file.name
                )
            }
        }

        if (defaultModelName.value.isNotEmpty()) {
            val loadedDefaultModel = allModels.find { model ->
                model["name"] == defaultModelName.value
            }
            if (loadedDefaultModel != null) {
                val destinationPath = File(directory, loadedDefaultModel["destination"].toString())
                if (loadedModelName.value == "") {
                    load(destinationPath.path, userThreads = user_thread.toInt())
                }
                currentDownloadable = Downloadable(
                    loadedDefaultModel["name"].toString(),
                    Uri.parse(loadedDefaultModel["source"].toString()),
                    destinationPath
                )
            }
        } else {
            allModels.find { model ->
                File(directory, model["destination"].toString()).exists()
            }?.let { model ->
                val destinationPath = File(directory, model["destination"].toString())
                if (loadedModelName.value == "") {
                    load(destinationPath.path, userThreads = user_thread.toInt())
                }
                currentDownloadable = Downloadable(
                    model["name"].toString(),
                    Uri.parse(model["source"].toString()),
                    destinationPath
                )
            }
        }
    }

    fun textToSpeech(context: Context) {
        if (!getIsSending()) {
            textToSpeech?.stop()

            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.let { tts ->
                        tts.language = Locale.US
                        tts.setSpeechRate(1.0f)

                        val utteranceId = UUID.randomUUID().toString()

                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onDone(utteranceId: String?) {
                                CoroutineScope(Dispatchers.Main).launch { stateForTextToSpeech = true }
                            }

                            override fun onError(utteranceId: String?) {
                                CoroutineScope(Dispatchers.Main).launch { stateForTextToSpeech = true }
                            }

                            override fun onStart(utteranceId: String?) {
                                CoroutineScope(Dispatchers.Main).launch { stateForTextToSpeech = false }
                            }
                        })

                        tts.speak(textForTextToSpeech, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    }
                }
            }
        }
    }

    fun stopTextToSpeech() {
        textToSpeech?.apply {
            stop()
            shutdown()
        }
        textToSpeech = null
        stateForTextToSpeech = true
    }

    var toggler by mutableStateOf(false)
    var showModal by mutableStateOf(true)
    var showAlert by mutableStateOf(false)
    var switchModal by mutableStateOf(false)
    var currentDownloadable: Downloadable? by mutableStateOf(null)

    override fun onCleared() {
        textToSpeech?.shutdown()
        super.onCleared()

        viewModelScope.launch {
            try {
                llamaAndroid.unload()
            } catch (exc: IllegalStateException) {
                addMessage("error", exc.message ?: "")
            }
        }
    }

    fun send() {
        val userMessage = removeExtraWhiteSpaces(message)
        message = ""

        if (userMessage.isNotBlank()) {
            if (first) {
                addMessage(
                    "system",
                    "This is a conversation between User and Iris, a friendly chatbot. Iris is helpful, kind, honest, good at writing, and never fails to answer any requests immediately and with precision."
                )
                addMessage("user", "Hi")
                addMessage("assistant", "How may I help You?")
                first = false
            }

            addMessage("user", userMessage)

            viewModelScope.launch {
                val hits = withContext(Dispatchers.IO) {
                    ragRepo.retrieve(userMessage, topK = 6)
                }
                val docContext = ragRepo.buildContextBlock(hits)

                val messagesForModel = if (!docContext.isNullOrBlank()) {
                    withDocContext(messages, docContext)
                } else {
                    messages
                }

                try {
                    llamaAndroid.send(llamaAndroid.getTemplate(messagesForModel))
                        .catch {
                            Log.e(TAG, "send() failed", it)
                            addMessage("error", it.message ?: "")
                        }
                        .collect { response ->
                            if (getIsMarked()) addMessage("codeBlock", response)
                            else addMessage("assistant", response)
                        }
                } finally {
                    if (!getIsCompleteEOT()) trimEOT()
                }
            }
        }
    }

    suspend fun unload() {
        llamaAndroid.unload()
    }

    var tokensList = mutableListOf<String>()
    var benchmarkStartTime: Long = 0L
    var tokensPerSecondsFinal: Double by mutableStateOf(0.0)
    var isBenchmarkingComplete by mutableStateOf(false)

    fun myCustomBenchmark() {
        viewModelScope.launch {
            try {
                tokensList.clear()
                benchmarkStartTime = System.currentTimeMillis()
                isBenchmarkingComplete = false

                launch {
                    while (!isBenchmarkingComplete) {
                        delay(1000L)
                        val elapsedTime = System.currentTimeMillis() - benchmarkStartTime
                        if (elapsedTime > 0) {
                            tokensPerSecondsFinal =
                                tokensList.size.toDouble() / (elapsedTime / 1000.0)
                        }
                    }
                }

                llamaAndroid.myCustomBenchmark().collect { emittedString ->
                    if (emittedString != null) tokensList.add(emittedString)
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Benchmark failed", exc)
            } finally {
                val elapsedTime = System.currentTimeMillis() - benchmarkStartTime
                tokensPerSecondsFinal = if (elapsedTime > 0) {
                    tokensList.size.toDouble() / (elapsedTime / 1000.0)
                } else 0.0
                isBenchmarkingComplete = true
            }
        }
    }

    var loadedModelName = mutableStateOf("")

    fun load(pathToModel: String, userThreads: Int) {
        viewModelScope.launch {
            try {
                llamaAndroid.unload()
            } catch (_: Exception) {
            }

            try {
                loadedModelName.value = pathToModel.substringAfterLast('/')
                newShowModal = false
                showModal = false
                showAlert = true
                llamaAndroid.load(
                    pathToModel,
                    userThreads = userThreads,
                    topK = topK,
                    topP = topP,
                    temp = temp
                )
                showAlert = false
            } catch (exc: IllegalStateException) {
                Log.e(TAG, "load() failed", exc)
            }

            showModal = false
            showAlert = false
            eot_str = llamaAndroid.send_eot_str()
        }
    }

    private fun addMessage(role: String, content: String) {
        val newMessage = mapOf("role" to role, "content" to content)

        messages = if (messages.isNotEmpty() && messages.last()["role"] == role) {
            val lastMessageContent = messages.last()["content"] ?: ""
            val updatedContent = "$lastMessageContent$content"
            val updatedLastMessage = messages.last() + ("content" to updatedContent)
            messages.toMutableList().apply { set(messages.lastIndex, updatedLastMessage) }
        } else {
            messages + listOf(newMessage)
        }
    }

    private fun trimEOT() {
        if (messages.isEmpty()) return
        val lastMessageContent = messages.last()["content"] ?: ""
        if (lastMessageContent.length < eot_str.length) return

        val updatedContent = lastMessageContent.slice(0..(lastMessageContent.length - eot_str.length))
        val updatedLastMessage = messages.last() + ("content" to updatedContent)
        messages = messages.toMutableList().apply { set(messages.lastIndex, updatedLastMessage) }
    }

    private fun removeExtraWhiteSpaces(input: String): String =
        input.replace("\\s+".toRegex(), " ")

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        messages = listOf()
        first = true
    }

    fun getIsSending(): Boolean = llamaAndroid.getIsSending()
    private fun getIsMarked(): Boolean = llamaAndroid.getIsMarked()
    fun getIsCompleteEOT(): Boolean = llamaAndroid.getIsCompleteEOT()
    fun stop() = llamaAndroid.stopTextGeneration()
}

fun sentThreadsValue() { }
