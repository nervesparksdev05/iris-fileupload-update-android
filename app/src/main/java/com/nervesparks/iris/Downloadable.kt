package com.nervesparks.iris

import android.app.DownloadManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.database.getLongOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri

data class Downloadable(val name: String, val source: Uri, val destination: File) {

    companion object {
        @JvmStatic
        private val tag: String? = this::class.qualifiedName

        sealed interface State
        data object Ready : State
        data class Downloading(val id: Long, val totalSize: Long) : State
        data class Downloaded(val downloadable: Downloadable) : State
        data class Error(val message: String) : State
        data object Stopped : State

        @JvmStatic
        @Composable
        fun Button(viewModel: MainViewModel, dm: DownloadManager, item: Downloadable) {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var status: State by remember {
                mutableStateOf(
                    when (val downloadId = getActiveDownloadId(dm, item)) {
                        null -> {
                            if (item.destination.exists() && item.destination.length() > 0 && !isPartialDownload(item.destination)) {
                                Downloaded(item)
                            } else {
                                Ready
                            }
                        }
                        else -> Downloading(downloadId, -1L)
                    }
                )
            }

            var progress by rememberSaveable { mutableDoubleStateOf(0.0) }
            var totalSize by rememberSaveable { mutableStateOf<Long?>(null) }

            suspend fun waitForDownload(result: Downloading, item: Downloadable): State {
                while (true) {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(result.id))
                        ?: return Error("DownloadManager query returned null")

                    if (!cursor.moveToFirst() || cursor.count < 1) {
                        cursor.close()
                        return Ready
                    }

                    val st = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

                    if (st == DownloadManager.STATUS_FAILED) {
                        cursor.close()
                        Log.e(tag, "Download FAILED for ${item.name} reason=$reason")
                        return Error("Download failed. reason=$reason")
                    }

                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        cursor.close()
                        Log.d(tag, "Download SUCCESSFUL: ${item.destination.path}")

                        withContext(Dispatchers.Main) {
                            if (!viewModel.allModels.any { it["name"] == item.name }) {
                                val newModel = mapOf(
                                    "name" to item.name,
                                    "source" to item.source.toString(),
                                    "destination" to item.destination.path
                                )
                                viewModel.allModels = viewModel.allModels + newModel
                                Log.d(tag, "Model added to viewModel: $newModel")
                            }
                        }

                        viewModel.currentDownloadable = item
                        if (viewModel.loadedModelName.value.isBlank()) {
                            viewModel.load(item.destination.path, userThreads = viewModel.user_thread.toInt())
                        }

                        return Downloaded(item)
                    }

                    val pix = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val tix = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val sofar = cursor.getLongOrNull(pix) ?: 0
                    val total = cursor.getLongOrNull(tix)?.takeIf { it > 0 } ?: 1

                    totalSize = if (total == 1L) null else total
                    cursor.close()

                    progress = (sofar * 1.0) / total
                    delay(1000L)
                }
            }

            LaunchedEffect(status) {
                if (status is Downloading) {
                    status = waitForDownload(status as Downloading, item)
                }
            }

            fun onClick() {
                when (val s = status) {
                    is Downloaded -> {
                        viewModel.showModal = true
                        viewModel.currentDownloadable = item
                        viewModel.load(item.destination.path, userThreads = viewModel.user_thread.toInt())
                    }

                    is Downloading -> {
                        Log.d(tag, "Already downloading")
                    }

                    else -> {
                        val request = DownloadManager.Request(item.source).apply {
                            setTitle("Downloading model")
                            setDescription("Downloading model: ${item.name}")
                            setAllowedNetworkTypes(
                                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                            )
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

                            // ✅ MOST IMPORTANT FIX:
                            setDestinationInExternalFilesDir(
                                context,
                                null,
                                item.destination.name
                            )
                        }

                        val id = try {
                            dm.enqueue(request)
                        } catch (e: Exception) {
                            Log.e(tag, "Download enqueue failed for ${item.name}", e)
                            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                            status = Error(e.message ?: "enqueue failed")
                            return
                        }

                        Toast.makeText(context, "Download started: ${item.name}", Toast.LENGTH_SHORT).show()
                        status = Downloading(id, -1L)

                        coroutineScope.launch {
                            status = waitForDownload(Downloading(id, -1L), item)
                        }
                    }
                }
            }

            fun onStop() {
                if (status is Downloading) {
                    dm.remove((status as Downloading).id)
                    status = Ready
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = { onClick() },
                    enabled = status !is Downloading && !viewModel.getIsSending(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    when (status) {
                        is Downloading -> Text(
                            text = buildAnnotatedString {
                                append("Downloading ")
                                withStyle(style = SpanStyle(color = Color.Cyan)) {
                                    append("${(progress * 100).toInt()}%")
                                }
                            },
                            color = Color.White
                        )

                        is Downloaded -> Text("Load", color = Color.White)
                        is Ready -> Text("Download", color = Color.White)
                        is Error -> Text("Retry", color = Color.White)
                        is Stopped -> Text("Stopped", color = Color.White)
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (status is Downloading) {
                    Button(
                        onClick = { onStop() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Text("Stop Download", color = Color.Black)
                    }
                }

                totalSize?.let {
                    Text(
                        text = "File size: ${it / (1024 * 1024)} MB",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

fun isPartialDownload(file: File): Boolean {
    return file.name.endsWith(".partial") ||
            file.name.endsWith(".download") ||
            file.name.endsWith(".tmp") ||
            file.name.contains(".part")
}

fun getActiveDownloadId(dm: DownloadManager, item: Downloadable): Long? {
    val query = DownloadManager.Query()
        .setFilterByStatus(
            DownloadManager.STATUS_RUNNING or
                    DownloadManager.STATUS_PENDING or
                    DownloadManager.STATUS_PAUSED
        )

    dm.query(query)?.use { cursor ->
        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
        val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)

        while (cursor.moveToNext()) {
            val currentUri = cursor.getString(uriIndex)
            if (currentUri == item.source.toString()) {
                return cursor.getLong(idIndex)
            }
        }
    }
    return null
}
