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
import kotlinx.coroutines.flow.collect
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

    val indexedDocs = ragRepo.observeDocs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearUserDocs() {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = ragRepo.snapshotDocs()
            docs.forEach { ragRepo.removeDocument(it.docId) }
        }
    }

    fun removeUserDoc(docId: String) {
        viewModelScope.launch(Dispatchers.IO) { ragRepo.removeDocument(docId) }
    }

    fun addUserDocs(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return

        uris.forEach { uri ->
            try {
                DocumentUriPermission.persistReadPermission(context, uri)
            } catch (t: Throwable) {
                Log.w(TAG, "persistReadPermission failed uri=$uri", t)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val stableUris = ArrayList<Uri>(uris.size)

            for (uri in uris) {
                runCatching { copyUriIntoAppStorage(context, uri) }
                    .onSuccess { stableUris += it }
                    .onFailure { t -> Log.e(TAG, "Failed to copy uri=$uri into app storage", t) }
            }

            if (stableUris.isNotEmpty()) ragRepo.addDocuments(stableUris)
        }
    }

    private fun copyUriIntoAppStorage(context: Context, uri: Uri): Uri {
        val resolver = context.contentResolver

        val meta = runCatching {
            com.nervesparks.iris.docs.DocumentTextExtractor.queryMeta(resolver, uri)
        }.getOrNull()

        val displayName = meta?.displayName ?: (uri.lastPathSegment ?: "document")
        val safeName = sanitizeFileName(displayName)

        val maxBytes = 100L * 1024L * 1024L
        if ((meta?.sizeBytes ?: 0L) > maxBytes) {
            throw IllegalStateException("File too large for offline indexing: ${meta?.sizeBytes} bytes")
        }

        val docsDir = File(context.filesDir, "user_docs").apply { mkdirs() }
        val outFile = File(docsDir, "${System.currentTimeMillis()}_${UUID.randomUUID()}_$safeName")

        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
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

    // -----------------------------
    // ✅ PROMPT WINDOWING
    // -----------------------------
    private fun windowedMessages(msgs: List<Map<String, String>>, keepLast: Int = 10): List<Map<String, String>> {
        val sys = msgs.firstOrNull { it["role"] == "system" }
        val rest = msgs.filter { it["role"] != "system" }.takeLast(keepLast)
        return if (sys != null) listOf(sys) + rest else rest
    }

    /**
     * ✅ IMPROVED: Strict document-only mode with clear instructions for all models
     * When documents are uploaded, the model can ONLY answer from documents.
     */
    private fun injectDocContextTransient(
        msgs: List<Map<String, String>>,
        docExcerpts: String?,
        hasReadyDocs: Boolean
    ): List<Map<String, String>> {
        // ✅ If no documents are ready, return normal conversation
        if (!hasReadyDocs) return msgs

        // ✅ If documents exist but no excerpts retrieved, force document-only mode
        if (docExcerpts.isNullOrBlank()) {
            val strictRule = mapOf(
                "role" to "system",
                "content" to """
DOCUMENT MODE: You have uploaded documents but no relevant excerpts were found for this question.

You MUST respond with: "I cannot find this information in the uploaded documents. Please try rephrasing your question or ask about a different topic from the document."

Do NOT use your general knowledge. Only answer from documents.
""".trimIndent()
            )

            val sysIdx = msgs.indexOfFirst { it["role"] == "system" }
            return if (sysIdx >= 0) {
                msgs.toMutableList().apply { add(sysIdx + 1, strictRule) }
            } else {
                listOf(strictRule) + msgs
            }
        }

        // ✅ When excerpts are available, provide them with clear instructions
        val strictRule = mapOf(
            "role" to "system",
            "content" to """
DOCUMENT MODE: Answer ONLY from the document excerpts below.

RULES:
1. Use ONLY information from the excerpts
2. If not found in excerpts, say: "I cannot find this in the documents."
3. Cite sources as [DocName §Section]
4. Do NOT use general knowledge

$docExcerpts
""".trimIndent()
        )

        val sysIdx = msgs.indexOfFirst { it["role"] == "system" }
        return if (sysIdx >= 0) {
            msgs.toMutableList().apply { add(sysIdx + 1, strictRule) }
        } else {
            listOf(strictRule) + msgs
        }
    }

    // -----------------------------
    // ✅ IMPROVED ROUTER - Always use docs when documents are ready
    // -----------------------------
    private fun shouldUseDocs(
        userMessage: String,
        readyDocsCount: Int,
        bestScore: Double,
        secondScore: Double,
        hitsCount: Int,
        lastWasDocs: Boolean
    ): Pair<Boolean, String> {
        // ✅ KEY CHANGE: If ANY documents are ready, ALWAYS use document mode
        // This ensures strict document-only behavior when documents exist
        if (readyDocsCount == 0) return false to "no_ready_docs"

        // ✅ Once documents are uploaded, we're ALWAYS in document mode
        return true to "documents_uploaded"
    }

    // Sticky doc mode tracking (kept for compatibility but simplified behavior)
    private var lastRouteWasDocs by mutableStateOf(false)
    private var docModeTurnsLeft by mutableIntStateOf(0)

    // ✅ lock retrieval to a single doc during follow-ups
    private var lockedDocId: String? by mutableStateOf(null)

    // ✅ reuse previous context for follow-up questions
    private var lastDocContext: String? by mutableStateOf(null)

    init { loadDefaultModelName() }

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

    var embeddingModels by mutableStateOf(
        listOf(
            mapOf(
                "name" to "bge-small-en-v1.5-q4_k_m.gguf",
                "source" to "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
                "destination" to "bge-small-en-v1.5-q4_k_m.gguf",
                "size" to "~25MB",
                "description" to "Required for document indexing & search"
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
                allModels += mapOf("name" to modelName, "source" to "local", "destination" to file.name)
            }
        }

        if (defaultModelName.value.isNotEmpty()) {
            val loadedDefaultModel = allModels.find { it["name"] == defaultModelName.value }
            if (loadedDefaultModel != null) {
                val destinationPath = File(directory, loadedDefaultModel["destination"].toString())
                if (loadedModelName.value == "") load(destinationPath.path, userThreads = user_thread.toInt())
                currentDownloadable = Downloadable(
                    loadedDefaultModel["name"].toString(),
                    Uri.parse(loadedDefaultModel["source"].toString()),
                    destinationPath
                )
            }
        } else {
            allModels.find { File(directory, it["destination"].toString()).exists() }?.let { model ->
                val destinationPath = File(directory, model["destination"].toString())
                if (loadedModelName.value == "") load(destinationPath.path, userThreads = user_thread.toInt())
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
        textToSpeech?.apply { stop(); shutdown() }
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
            try { llamaAndroid.unload() }
            catch (exc: IllegalStateException) { addMessage("error", exc.message ?: "") }
        }
    }

    fun send() {
        val userMessage = removeExtraWhiteSpaces(message).trim()
        message = ""
        if (userMessage.isBlank()) return

        if (first) {
            addMessage(
                "system",
                "This is a conversation between User and Iris, a friendly chatbot. Iris is helpful, kind, honest, good at writing, and answers requests with precision."
            )
            addMessage("user", "Hi")
            addMessage("assistant", "How may I help you?")
            first = false
        }

        addMessage("user", userMessage)

        viewModelScope.launch {
            val docsSnapshot = withContext(Dispatchers.IO) { ragRepo.snapshotDocs() }
            val readyDocs = docsSnapshot.filter { it.status.equals("READY", ignoreCase = true) }

            val explicitFileAsk =
                userMessage.contains("file", true) ||
                        userMessage.contains("document", true) ||
                        userMessage.contains("doc", true) ||  // ✅ Added 'doc' abbreviation
                        userMessage.contains("pdf", true) ||
                        userMessage.contains("resume", true) ||
                        userMessage.contains("uploaded", true)

            // ✅ Add detailed logging for debugging
            Log.i(TAG, "send: docsSnapshot=${docsSnapshot.size} readyDocs=${readyDocs.size} explicitFileAsk=$explicitFileAsk")
            docsSnapshot.forEach { doc ->
                Log.d(TAG, "  doc: name=${doc.name} status=${doc.status} docId=${doc.docId}")
            }

            if (explicitFileAsk && readyDocs.isEmpty()) {
                val indexing = docsSnapshot.any { it.status.equals("INDEXING", ignoreCase = true) }
                val failed = docsSnapshot.any { it.status.equals("FAILED", ignoreCase = true) }
                addMessage(
                    "assistant",
                    when {
                        indexing -> "I'm still indexing your document(s). Please wait a moment and try again."
                        failed -> "Document indexing failed. Please try uploading the document again."
                        else -> "I don't have any indexed documents yet. Please upload a document first."
                    }
                )
                return@launch
            }

            val preferredDoc = readyDocs.maxByOrNull { it.createdAt }

            // If user explicitly asks about docs, lock to latest READY doc immediately
            if (explicitFileAsk && lockedDocId == null) {
                lockedDocId = preferredDoc?.docId
            }

            val retrievalQuery = userMessage

            // ✅ restrict retrieval to locked doc during doc-mode followups
            val docFilter = if (docModeTurnsLeft > 0 || explicitFileAsk) lockedDocId else null

            val hits = withContext(Dispatchers.IO) {
                if (readyDocs.isEmpty()) emptyList()
                else ragRepo.retrieve(
                    query = retrievalQuery,
                    topK = 8,  // ✅ Increased for more context variety
                    scoreThreshold = 0.05,  // ✅ Lowered for better recall
                    docIdFilter = docFilter
                )
            }

            val best = hits.firstOrNull()?.score ?: 0.0
            val second = hits.getOrNull(1)?.score ?: 0.0

            // ✅ Use improved router - always true when docs exist
            val (useDocs, reason) = shouldUseDocs(
                userMessage = userMessage,
                readyDocsCount = readyDocs.size,
                bestScore = best,
                secondScore = second,
                hitsCount = hits.size,
                lastWasDocs = lastRouteWasDocs
            )

            // Update tracking (kept for potential future use)
            lastRouteWasDocs = useDocs
            if (useDocs) {
                docModeTurnsLeft = 2
                if (lockedDocId == null) {
                    lockedDocId = preferredDoc?.docId ?: hits.firstOrNull()?.docId
                }
            } else {
                docModeTurnsLeft = 0
                lockedDocId = null
                lastDocContext = null
            }

            Log.i(
                TAG,
                "route: useDocs=$useDocs reason=$reason ready=${readyDocs.size} hits=${hits.size} best=$best second=$second lockedDocId=$lockedDocId"
            )

            // ✅ Build context with fallback options - IMPROVED sizes
            val docExcerpts: String? = when {
                useDocs && hits.isNotEmpty() -> {
                    // Has good retrieval hits - use larger context
                    Log.d(TAG, "Using ${hits.size} retrieval hits for context")
                    ragRepo.buildContextBlock(hits, maxChars = 2400)
                        .also { lastDocContext = it }
                }
                useDocs && lockedDocId != null -> {
                    // No hits but we have a locked doc - get more chunks for better coverage
                    Log.d(TAG, "No hits, using fallback chunks from lockedDocId=$lockedDocId")
                    val fallback = withContext(Dispatchers.IO) {
                        ragRepo.fallbackTopChunksForDoc(lockedDocId!!, maxChunks = 12)
                    }
                    Log.d(TAG, "Fallback returned ${fallback.size} chunks")
                    ragRepo.buildContextBlock(fallback, maxChars = 2000)
                        .also { lastDocContext = it }
                }
                useDocs && readyDocs.isNotEmpty() -> {
                    // ✅ NEW: Use first ready doc if no locked doc
                    val firstReadyDoc = readyDocs.first()
                    Log.d(TAG, "Using first ready doc as fallback: ${firstReadyDoc.name}")
                    val fallback = withContext(Dispatchers.IO) {
                        ragRepo.fallbackTopChunksForDoc(firstReadyDoc.docId, maxChunks = 12)
                    }
                    Log.d(TAG, "First doc fallback returned ${fallback.size} chunks")
                    ragRepo.buildContextBlock(fallback, maxChars = 2000)
                        .also { 
                            lastDocContext = it
                            lockedDocId = firstReadyDoc.docId
                        }
                }
                useDocs -> {
                    // In doc mode but no specific context - will trigger "not found" response
                    Log.w(TAG, "useDocs=true but no context available!")
                    null
                }
                else -> null
            }
            
            Log.d(TAG, "docExcerpts length=${docExcerpts?.length ?: 0}")

            // ✅ Build prompt with strict document-only mode
            var base = windowedMessages(messages, keepLast = 10)
            val messagesForModel = injectDocContextTransient(base, docExcerpts, readyDocs.isNotEmpty())

            var template = llamaAndroid.getTemplate(messagesForModel)
            var promptLen = template.length

            if (promptLen > 18_000) {
                base = windowedMessages(messages, keepLast = 6)
                val shrunken = injectDocContextTransient(base, docExcerpts, readyDocs.isNotEmpty())
                template = llamaAndroid.getTemplate(shrunken)
                promptLen = template.length
                Log.w(TAG, "prompt too big; reduced keepLast=6 newPromptChars=$promptLen")
            }

            var firstTokenLogged = false
            val tSendStart = System.currentTimeMillis()

            try {
                llamaAndroid.send(template)
                    .catch {
                        Log.e(TAG, "send() failed", it)
                        addMessage("error", it.message ?: "")
                    }
                    .collect { response ->
                        if (!firstTokenLogged) {
                            firstTokenLogged = true
                            Log.i(TAG, "timing: firstToken=${System.currentTimeMillis() - tSendStart}ms")
                        }
                        if (getIsMarked()) addMessage("codeBlock", response)
                        else addMessage("assistant", response)
                    }
            } finally {
                if (!getIsCompleteEOT()) trimEOT()
            }
        }
    }

    suspend fun unload() { llamaAndroid.unload() }

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
                            tokensPerSecondsFinal = tokensList.size.toDouble() / (elapsedTime / 1000.0)
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
            try { llamaAndroid.unload() } catch (_: Exception) {}

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
        val fixedLastMessage = messages.last() + ("content" to updatedContent)
        messages = messages.toMutableList().apply { set(messages.lastIndex, fixedLastMessage) }
    }

    private fun removeExtraWhiteSpaces(input: String): String = input.replace("\\s+".toRegex(), " ")

    fun updateMessage(newMessage: String) { message = newMessage }

    fun clear() {
        messages = listOf()
        first = true
        lastRouteWasDocs = false
        docModeTurnsLeft = 0
        lockedDocId = null
        lastDocContext = null
    }

    fun getIsSending(): Boolean = llamaAndroid.getIsSending()
    private fun getIsMarked(): Boolean = llamaAndroid.getIsMarked()
    fun getIsCompleteEOT(): Boolean = llamaAndroid.getIsCompleteEOT()
    fun stop() = llamaAndroid.stopTextGeneration()
}

fun sentThreadsValue() { }