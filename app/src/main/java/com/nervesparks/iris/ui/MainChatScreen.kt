package com.nervesparks.iris.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.nervesparks.iris.Downloadable
import com.nervesparks.iris.LinearGradient
import com.nervesparks.iris.MainViewModel
import com.nervesparks.iris.R
import com.nervesparks.iris.docs.DocumentTextExtractor
import com.nervesparks.iris.ui.components.DownloadModal
import com.nervesparks.iris.ui.components.LoadingModal
import com.nervesparks.iris.ui.components.UploadedDocsRow
import kotlinx.coroutines.launch
import java.io.File

// ✅ NEW: chip model (what shows at bottom like your screenshot)
private data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainChatScreen(
    onNextButtonClicked: () -> Unit,
    viewModel: MainViewModel,
    clipboard: ClipboardManager,
    dm: DownloadManager,
    models: List<Downloadable>,
    extFileDir: File?,
) {
    val kc = LocalSoftwareKeyboardController.current
    val windowInsets = WindowInsets.ime
    val focusManager = LocalFocusManager.current
    println("Thread started: ${Thread.currentThread().name}")

    val Prompts = listOf(
        "Explain how to develop a consistent reading habit.",
        "Write an email to your manager requesting leave for a day.",
        "Suggest time management strategies for handling multiple deadlines effectively.",
        "Draft a professional LinkedIn message to connect with a recruiter.",
        "Suggest ways to practice mindfulness in a busy daily schedule.",
        "Recommend a 15-minute daily workout routine to stay fit with a busy schedule.",
        "Provide a simple and polite Spanish translation of 'Excuse me, can you help me?' with explanation.",
        "List the top 5 science fiction novels that have most influenced modern technological thinking",
        "List security tips to protect personal information online.",
        "Recommend three books that can improve communication skills."
    )

    val allModelsExist = models.all { model -> model.destination.exists() }
    val Prompts_Home = listOf(
        "Explains complex topics simply.",
        "Remembers previous inputs.",
        "May sometimes be inaccurate.",
        "Unable to provide current affairs due to no internet connectivity."
    )

    var recognizedText by remember { mutableStateOf("") }
    val speechRecognizerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            recognizedText = results?.get(0) ?: ""
            viewModel.updateMessage(recognizedText)
        }

    val context = LocalContext.current

    // ✅ Use indexed docs (Room-backed) instead of old userDocs list
    val docs = viewModel.indexedDocs.value
    val docCount = docs.size
    val maxDocs = 3

    // ✅ NEW: what we show as bottom “file chips” (like your screenshot)
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }

    // 📎 Upload documents
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult

        val remaining: Int = (maxDocs - docCount).coerceAtLeast(0)
        val toAdd: List<Uri> = if (uris.size > remaining) uris.take(remaining) else uris

        if (toAdd.isNotEmpty()) {
            // ✅ Start indexing (your existing behavior)
            viewModel.addUserDocs(context, toAdd)
            Toast.makeText(context, "Added ${toAdd.size} document(s)", Toast.LENGTH_SHORT).show()

            // ✅ NEW: update bottom chip(s)
            val metas = toAdd.map { uri ->
                val meta = DocumentTextExtractor.queryMeta(context.contentResolver, uri)
                PendingAttachment(
                    uri = uri,
                    name = meta.displayName,
                    mime = meta.mime
                )
            }
            pendingAttachments = (pendingAttachments + metas).distinctBy { it.uri.toString() }
        }

        if (uris.size > remaining) {
            Toast.makeText(context, "Upload cap is $maxDocs documents", Toast.LENGTH_SHORT).show()
        }
    }

    val focusRequester = FocusRequester()
    var isFocused by remember { mutableStateOf(false) }
    var textFieldBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    if (allModelsExist) {
        viewModel.showModal = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LinearGradient()

        Column {
            if (viewModel.showModal) {
                DownloadModal(viewModel = viewModel, dm = dm, models = models)
            }

            if (viewModel.showAlert) {
                LoadingModal(viewModel)
            }

            Column {
                val scrollState = rememberLazyListState()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { kc?.hide() },
                                onDoubleTap = { kc?.hide() },
                                onLongPress = { kc?.hide() },
                                onPress = { kc?.hide() },
                            )
                        }
                ) {
                    if (viewModel.messages.isEmpty() && !viewModel.showModal && !viewModel.showAlert) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentHeight(Alignment.CenterVertically),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text(
                                    text = "Hello, Ask me Anything",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.W300,
                                        letterSpacing = 1.sp,
                                        fontSize = 50.sp,
                                        lineHeight = 60.sp
                                    ),
                                    fontFamily = FontFamily.SansSerif,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .wrapContentHeight()
                                )
                            }

                            items(Prompts_Home.size) { index ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .padding(8.dp)
                                        .background(
                                            Color(0xFF010825),
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(Color.White, shape = CircleShape)
                                                .padding(4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.info_svgrepo_com),
                                                contentDescription = null,
                                                tint = Color.Black
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Text(
                                            text = Prompts_Home.getOrNull(index) ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                                            textAlign = TextAlign.Start,
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }

                            item { }
                        }
                    } else {
                        LazyColumn(state = scrollState) {
                            val length = viewModel.messages.size

                            itemsIndexed(
                                viewModel.messages.slice(3 until length) as? List<Map<String, String>>
                                    ?: emptyList()
                            ) { _, messageMap ->
                                val role = messageMap["role"] ?: ""
                                val content = messageMap["content"] ?: ""
                                val trimmedMessage = if (content.endsWith("\n")) {
                                    content.substring(0, content.length - 1)
                                } else content

                                if (role != "system") {
                                    if (role != "codeBlock") {
                                        Box {
                                            val ctx = LocalContext.current
                                            val interactionSource = remember { MutableInteractionSource() }
                                            val sheetState = rememberModalBottomSheetState()
                                            var isSheetOpen by rememberSaveable { mutableStateOf(false) }

                                            if (isSheetOpen) {
                                                MessageBottomSheet(
                                                    message = trimmedMessage,
                                                    clipboard = clipboard,
                                                    context = ctx,
                                                    viewModel = viewModel,
                                                    onDismiss = {
                                                        isSheetOpen = false
                                                        viewModel.toggler = false
                                                    },
                                                    sheetState = sheetState
                                                )
                                            }

                                            Row(
                                                horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 0.dp),
                                            ) {
                                                if (role == "assistant") {
                                                    Image(
                                                        painter = painterResource(id = R.drawable.logo),
                                                        contentDescription = "Bot Icon",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .padding(horizontal = 2.dp)
                                                        .background(
                                                            color = if (role == "user") Color(0xFF171E2C) else Color.Transparent,
                                                            shape = RoundedCornerShape(12.dp),
                                                        )
                                                        .combinedClickable(
                                                            interactionSource = interactionSource,
                                                            indication = ripple(color = Color.Gray),
                                                            onLongClick = {
                                                                if (viewModel.getIsSending()) {
                                                                    Toast.makeText(
                                                                        ctx,
                                                                        " Wait till generation is done! ",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                } else {
                                                                    isSheetOpen = true
                                                                }
                                                            },
                                                            onClick = { kc?.hide() }
                                                        )
                                                ) {
                                                    Row(modifier = Modifier.padding(5.dp)) {
                                                        Box(
                                                            modifier = Modifier
                                                                .widthIn(max = 300.dp)
                                                                .padding(3.dp)
                                                        ) {
                                                            Text(
                                                                text = if (trimmedMessage.startsWith("```")) {
                                                                    trimmedMessage.substring(3)
                                                                } else trimmedMessage,
                                                                style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFA0A0A5)),
                                                                modifier = Modifier.padding(start = 1.dp, end = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                if (role == "user") {
                                                    Image(
                                                        painter = painterResource(id = R.drawable.user_icon),
                                                        contentDescription = "Human Icon",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        val ctx = LocalContext.current
                                        val interactionSource = remember { MutableInteractionSource() }
                                        val sheetState = rememberModalBottomSheetState()
                                        var isSheetOpen by rememberSaveable { mutableStateOf(false) }

                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                                                .fillMaxWidth()
                                        ) {
                                            if (isSheetOpen) {
                                                MessageBottomSheet(
                                                    message = trimmedMessage,
                                                    clipboard = clipboard,
                                                    context = ctx,
                                                    viewModel = viewModel,
                                                    onDismiss = {
                                                        isSheetOpen = false
                                                        viewModel.toggler = false
                                                    },
                                                    sheetState = sheetState
                                                )
                                            }

                                            Column(
                                                modifier = Modifier.combinedClickable(
                                                    interactionSource = interactionSource,
                                                    indication = ripple(color = Color.LightGray),
                                                    onLongClick = {
                                                        if (viewModel.getIsSending()) {
                                                            Toast.makeText(
                                                                ctx,
                                                                " Wait till generation is done! ",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            isSheetOpen = true
                                                        }
                                                    },
                                                    onClick = { kc?.hide() }
                                                )
                                            ) {
                                                Text(
                                                    text = if (trimmedMessage.startsWith("```")) {
                                                        trimmedMessage.substring(3)
                                                    } else trimmedMessage,
                                                    style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFA0A0A5)),
                                                    modifier = Modifier.padding(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(
                                    modifier = Modifier
                                        .height(1.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }

                        ScrollToBottomButton(
                            scrollState = scrollState,
                            messages = viewModel.messages,
                            viewModel = viewModel
                        )
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(Prompts.size) { index ->
                        if (viewModel.messages.size <= 1) {
                            Card(
                                modifier = Modifier
                                    .height(100.dp)
                                    .clickable {
                                        viewModel.updateMessage(Prompts[index])
                                        focusRequester.requestFocus()
                                    }
                                    .padding(horizontal = 8.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF030815))
                            ) {
                                Text(
                                    text = Prompts[index],
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFA0A0A5),
                                        fontSize = 12.sp,
                                    ),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .width(200.dp)
                                        .height(100.dp)
                                        .padding(horizontal = 15.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF050B16))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {

                        // ✅ show uploaded docs from Room-backed list
                        if (docs.isNotEmpty()) {
                            UploadedDocsRow(
                                docs = docs,
                                onRemove = { id ->
                                    viewModel.removeUserDoc(id)
                                    Toast.makeText(context, "Removed document", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // ✅ NEW: bottom “file chip” row like your screenshot
                        AttachmentChipRow(
                            items = pendingAttachments,
                            onRemove = { uri ->
                                pendingAttachments = pendingAttachments.filterNot { it.uri == uri }
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 5.dp, top = 8.dp, bottom = 12.dp, end = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 📎 Upload icon
                            IconButton(
                                onClick = {
                                    if (docCount >= maxDocs) {
                                        Toast.makeText(
                                            context,
                                            "Upload cap is $maxDocs documents",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        docPickerLauncher.launch(
                                            arrayOf(
                                                "application/pdf",
                                                "text/*",
                                                "application/msword",
                                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                "application/vnd.ms-excel",
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                "application/json",
                                                "application/*"
                                            )
                                        )
                                    }
                                    focusManager.clearFocus()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach documents",
                                    tint = Color(0xFFDDDDE4),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
                                }
                                speechRecognizerLauncher.launch(intent)
                                focusManager.clearFocus()
                            }) {
                                Icon(
                                    modifier = Modifier.size(25.dp),
                                    painter = painterResource(id = R.drawable.microphone_new_svgrepo_com),
                                    contentDescription = "Mic",
                                    tint = Color(0xFFDDDDE4)
                                )
                            }

                            val dragSelection = remember { mutableStateOf<TextRange?>(null) }
                            val lastKnownText = remember { mutableStateOf(viewModel.message) }

                            val textFieldValue = remember {
                                mutableStateOf(
                                    TextFieldValue(
                                        text = viewModel.message,
                                        selection = TextRange(viewModel.message.length)
                                    )
                                )
                            }

                            TextField(
                                value = textFieldValue.value.copy(
                                    text = viewModel.message,
                                    selection = when {
                                        viewModel.message != lastKnownText.value -> textFieldValue.value.selection
                                        else -> dragSelection.value ?: textFieldValue.value.selection
                                    }
                                ),
                                onValueChange = { newValue ->
                                    dragSelection.value =
                                        if (newValue.text == textFieldValue.value.text) newValue.selection else null

                                    textFieldValue.value = newValue
                                    lastKnownText.value = newValue.text
                                    viewModel.updateMessage(newValue.text)
                                },
                                placeholder = { Text("Message") },
                                modifier = Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coordinates ->
                                        textFieldBounds = coordinates.boundsInRoot()
                                    }
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        isFocused = focusState.isFocused
                                    },
                                shape = RoundedCornerShape(size = 18.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFBECBD1),
                                    unfocusedTextColor = Color(0xFFBECBD1),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedLabelColor = Color(0xFF626568),
                                    cursorColor = Color(0xFF626568),
                                    unfocusedContainerColor = Color(0xFF171E2C),
                                    focusedContainerColor = Color(0xFF22314A)
                                )
                            )

                            if (!viewModel.getIsSending()) {

                                IconButton(onClick = {
                                    if (viewModel.loadedModelName.value == "") {
                                        focusManager.clearFocus()
                                        Toast.makeText(context, "Load A Model First", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // If user refers to "the file / this PDF" and there are pending attachments,
                                        // add a lightweight hint so RAG can target the right document.
                                        val current = viewModel.message
                                        val refersToFile = current.contains("file", true) ||
                                                current.contains("document", true) ||
                                                current.contains("pdf", true) ||
                                                current.contains("this", true)

                                        if (refersToFile && pendingAttachments.isNotEmpty()) {
                                            val names = pendingAttachments.take(3).joinToString { it.name }
                                            if (names.isNotBlank() && !current.contains(names, ignoreCase = true)) {
                                                viewModel.updateMessage("$current (about uploaded document(s): $names)")
                                            }
                                        }

                                        viewModel.send()
                                        focusManager.clearFocus()
                                    }
                                }) {
                                    Icon(
                                        modifier = Modifier.size(30.dp),
                                        painter = painterResource(id = R.drawable.send_2_svgrepo_com),
                                        contentDescription = "Send",
                                        tint = Color(0xFFDDDDE4)
                                    )
                                }
                            } else {
                                IconButton(onClick = { viewModel.stop() }) {
                                    Icon(
                                        modifier = Modifier.size(28.dp),
                                        painter = painterResource(id = R.drawable.square_svgrepo_com),
                                        contentDescription = "Stop",
                                        tint = Color(0xFFDDDDE4)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ✅ NEW: chip row (looks like your screenshot) */
@Composable
private fun AttachmentChipRow(
    items: List<PendingAttachment>,
    onRemove: (Uri) -> Unit,
) {
    AnimatedVisibility(visible = items.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 10.dp)
        ) {
            itemsIndexed(items) { _, it ->
                AttachmentChip(
                    name = it.name,
                    mime = it.mime,
                    onClose = { onRemove(it.uri) }
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    name: String,
    mime: String,
    onClose: () -> Unit,
) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val badge = when {
        mime == "application/pdf" || ext == "pdf" -> "PDF"
        ext.isNotBlank() -> ext.uppercase()
        else -> "FILE"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1020)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFB3261E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/* ---------------------------
   The rest of your file below
   is unchanged
   --------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScrollState = rememberLazyListState()
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF01081a),
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = "Settings",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = TextAlign.Center
            )
            LazyColumn(state = sheetScrollState) {
                item {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFF14161f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .border(
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = Color.LightGray.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Select thread for process, 0 for default",
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "${viewModel.user_thread.toInt()}",
                                color = Color.White
                            )
                            Slider(
                                value = viewModel.user_thread,
                                onValueChange = { viewModel.user_thread = it },
                                valueRange = 0f..8f,
                                steps = 7,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF6200EE),
                                    activeTrackColor = Color(0xFF6200EE),
                                    inactiveTrackColor = Color.Gray
                                ),
                            )
                            Spacer(modifier = Modifier.height(15.dp))
                            Text(
                                text = "After changing thread please Save the changes!!",
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(15.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.DarkGray.copy(alpha = 1.0f),
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(8.dp),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 6.dp,
                                    pressedElevation = 3.dp
                                ),
                                onClick = {
                                    viewModel.currentDownloadable?.destination?.path?.let {
                                        viewModel.load(it, viewModel.user_thread.toInt())
                                    }
                                }
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScrollToBottomButton(
    viewModel: MainViewModel,
    scrollState: LazyListState,
    messages: List<Any>
) {
    val coroutineScope = rememberCoroutineScope()
    var isAutoScrolling by remember { mutableStateOf(false) }
    var isButtonVisible by remember { mutableStateOf(true) }

    val canScrollDown by remember {
        derivedStateOf { scrollState.canScrollForward }
    }

    LaunchedEffect(viewModel.messages.size, isAutoScrolling) {
        if (isAutoScrolling) {
            coroutineScope.launch {
                scrollState.scrollToItem(viewModel.messages.size + 1)
            }
        }
    }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            isAutoScrolling = false
            isButtonVisible = true
        }
    }

    LaunchedEffect(messages.lastOrNull()) {
        if (isAutoScrolling && messages.isNotEmpty()) {
            coroutineScope.launch {
                scrollState.scrollToItem(viewModel.messages.size + 1)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = (canScrollDown || isAutoScrolling) && isButtonVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FloatingActionButton(
                onClick = {
                    isAutoScrolling = true
                    isButtonVisible = false
                    coroutineScope.launch {
                        scrollState.scrollToItem(viewModel.messages.size + 1)
                    }
                },
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .size(56.dp),
                shape = RoundedCornerShape(percent = 50),
                containerColor = Color.White.copy(alpha = 0.5f),
                contentColor = Color.Black
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun ModelSelectorWithDownloadModal(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    extFileDir: File?
) {
    val context = LocalContext.current as Activity
    val coroutineScope = rememberCoroutineScope()

    var mExpanded by remember { mutableStateOf(false) }
    var mSelectedText by remember { mutableStateOf("") }
    var mTextFieldSize by remember { mutableStateOf(Size.Zero) }
    var selectedModel by remember { mutableStateOf<Map<String, Any>?>(null) }

    val icon = if (mExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown

    val localModels = remember(extFileDir) {
        extFileDir?.listFiles { _, name -> name.endsWith(".gguf") }
            ?.map { file ->
                mapOf(
                    "name" to file.nameWithoutExtension,
                    "source" to file.toURI().toString(),
                    "destination" to file.name
                )
            } ?: emptyList()
    }

    val combinedModels = remember(viewModel.allModels, localModels) {
        (viewModel.allModels + localModels).distinctBy { it["name"] }
    }
    viewModel.allModels = combinedModels

    Column(Modifier.padding(20.dp)) {
        OutlinedTextField(
            value = viewModel.loadedModelName.value,
            onValueChange = { mSelectedText = it },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    mTextFieldSize = coordinates.size.toSize()
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { mExpanded = !mExpanded },
                        onPress = { mExpanded = !mExpanded }
                    )
                }
                .clickable { mExpanded = !mExpanded },
            label = { Text("Select Model") },
            trailingIcon = {
                Icon(
                    icon,
                    contentDescription = "Toggle dropdown",
                    Modifier.clickable { mExpanded = !mExpanded },
                    tint = Color(0xFFcfcfd1)
                )
            },
            textStyle = TextStyle(color = Color(0xFFf5f5f5)),
            readOnly = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color(0xFF666666),
                focusedBorderColor = Color(0xFFcfcfd1),
                unfocusedLabelColor = Color(0xFF666666),
                focusedLabelColor = Color(0xFFcfcfd1),
                unfocusedTextColor = Color(0xFFf5f5f5),
                focusedTextColor = Color(0xFFf7f5f5),
            )
        )

        DropdownMenu(
            modifier = Modifier
                .background(Color(0xFF01081a))
                .width(with(LocalDensity.current) { mTextFieldSize.width.toDp() })
                .padding(top = 2.dp)
                .border(1.dp, color = Color.LightGray.copy(alpha = 0.5f)),
            expanded = mExpanded,
            onDismissRequest = { mExpanded = false }
        ) {
            viewModel.allModels.forEach { model ->
                DropdownMenuItem(
                    modifier = Modifier
                        .background(color = Color(0xFF090b1a))
                        .padding(horizontal = 1.dp, vertical = 0.dp),
                    onClick = {
                        mSelectedText = model["name"].toString()
                        selectedModel = model
                        mExpanded = false

                        val downloadable = Downloadable(
                            name = model["name"].toString(),
                            source = Uri.parse(model["source"].toString()),
                            destination = File(extFileDir, model["destination"].toString())
                        )

                        viewModel.showModal = true
                        viewModel.currentDownloadable = downloadable
                    }
                ) {
                    model["name"]?.let { Text(text = it, color = Color.White) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBottomSheet(
    message: String,
    clipboard: ClipboardManager,
    context: Context,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    ModalBottomSheet(
        sheetState = sheetState,
        containerColor = Color(0xFF01081a),
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .background(color = Color(0xFF01081a))
        ) {
            val sheetScrollState = rememberLazyListState()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
            ) {
                TextButton(
                    colors = ButtonDefaults.buttonColors(Color(0xFF171E2C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    onClick = {
                        clipboard.setText(AnnotatedString(message))
                        Toast.makeText(context, "Text copied!", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                ) {
                    Text(text = "Copy Text", color = Color(0xFFA0A0A5))
                }

                TextButton(
                    colors = ButtonDefaults.buttonColors(Color(0xFF171E2C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = !viewModel.getIsSending(),
                    onClick = { viewModel.toggler = !viewModel.toggler }
                ) {
                    Text(text = "Select Text To Copy", color = Color(0xFFA0A0A5))
                }

                TextButton(
                    colors = ButtonDefaults.buttonColors(Color(0xFF171E2C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = !viewModel.getIsSending(),
                    onClick = {
                        if (viewModel.stateForTextToSpeech) {
                            viewModel.textForTextToSpeech = message
                            viewModel.textToSpeech(context)
                        } else {
                            viewModel.stopTextToSpeech()
                        }
                        onDismiss()
                    }
                ) {
                    Text(
                        text = if (viewModel.stateForTextToSpeech) "Text To Speech" else "Stop",
                        color = Color(0xFFA0A0A5))
                }

                LazyColumn(state = sheetScrollState) {
                    item {
                        SelectionContainer {
                            if (viewModel.toggler) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(color = Color.Black)
                                        .padding(25.dp)
                                ) {
                                    Text(
                                        text = AnnotatedString(message),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
