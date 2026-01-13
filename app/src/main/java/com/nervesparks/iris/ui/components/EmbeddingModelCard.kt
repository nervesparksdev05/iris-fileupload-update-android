package com.nervesparks.iris.ui.components

import android.app.DownloadManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervesparks.iris.MainViewModel
import com.nervesparks.iris.irisapp.ServiceLocator
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun EmbeddingModelCard(
    modelName: String,
    modelSize: String,
    modelDescription: String,
    viewModel: MainViewModel, // kept for compatibility (unused)
    dm: DownloadManager,
    extFilesDir: File,
    downloadLink: String
) {
    val context = LocalContext.current
    val destinationFile = File(extFilesDir, modelName)

    val accentBlue = Color(0xFF2563EB)
    val dangerRed = Color(0xFFEF4444)
    val activeGreen = Color(0xFF00D100)

    var isDownloaded by remember { mutableStateOf(destinationFile.exists() && destinationFile.length() > 0L) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var currentDownloadId by remember { mutableLongStateOf(-1L) }
    var isDefaultModel by remember { mutableStateOf(false) }

    fun cancelAndDelete() {
        if (currentDownloadId != -1L) {
            dm.remove(currentDownloadId) // cancel DownloadManager task
            currentDownloadId = -1L
        }
        isDownloading = false
        downloadProgress = 0f

        if (destinationFile.exists()) destinationFile.delete()
        isDownloaded = false
        isDefaultModel = false

        Toast.makeText(context, "Embedding model deleted", Toast.LENGTH_SHORT).show()
    }

    // Poll download progress
    LaunchedEffect(isDownloading, currentDownloadId) {
        if (isDownloading && currentDownloadId != -1L) {
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(currentDownloadId)
                val cursor = dm.query(query)

                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    val status = cursor.getInt(statusIdx)
                    val bytesDownloaded = cursor.getLong(bytesDownloadedIdx)
                    val bytesTotal = cursor.getLong(bytesTotalIdx)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isDownloading = false
                            isDownloaded = true
                            downloadProgress = 1f
                            currentDownloadId = -1L
                            ServiceLocator.ensureEmbeddingReady(context)
                        }

                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            downloadProgress = 0f
                            currentDownloadId = -1L
                        }

                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                            if (bytesTotal > 0) {
                                downloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                            }
                        }
                    }
                }
                cursor.close()
                delay(500)
            }
        }
    }

    // Backup check: if file appears
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (!isDownloaded && destinationFile.exists() && destinationFile.length() > 0L) {
                isDownloaded = true
                isDownloading = false
                ServiceLocator.ensureEmbeddingReady(context)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1221)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            when {
                isDownloading -> {
                    // Title row
                    Text(
                        text = modelName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = accentBlue,
                        trackColor = Color(0xFF1E293B),
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%  Downloading...",
                            color = Color(0xFF6B7280),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { cancelAndDelete() },
                            colors = ButtonDefaults.buttonColors(containerColor = dangerRed),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Delete", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                !isDownloaded -> {
                    // Before download state
                    Text(
                        text = modelName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (destinationFile.exists()) destinationFile.delete()

                            val request = DownloadManager.Request(Uri.parse(downloadLink)).apply {
                                setTitle("Embedding Model")
                                setDescription("Downloading $modelName for document processing")
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationUri(Uri.fromFile(destinationFile))
                                setAllowedNetworkTypes(
                                    DownloadManager.Request.NETWORK_WIFI or
                                            DownloadManager.Request.NETWORK_MOBILE
                                )
                            }

                            currentDownloadId = dm.enqueue(request)
                            isDownloading = true
                            downloadProgress = 0f

                            Toast.makeText(context, "Downloading $modelName...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentBlue),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(text = "Download", color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Not Downloaded",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                else -> {
                    // After download state - matching the image design
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Model",
                            color = activeGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        if (isDefaultModel) {
                            Text(
                                text = "Default",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = modelName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.height(16.dp))

                    // Load and Delete buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // Handle Load action
                                Toast.makeText(context, "Loading $modelName...", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentBlue),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Load", color = Color.White, fontSize = 14.sp)
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = { cancelAndDelete() },
                            colors = ButtonDefaults.buttonColors(containerColor = dangerRed),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Delete", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Radio button row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = isDefaultModel,
                            onClick = { isDefaultModel = !isDefaultModel },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = activeGreen,
                                unselectedColor = Color(0xFF6B7280)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Set as Default Model",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Size information
                    Text(
                        text = "Size: $modelSize",
                        color = Color(0xFF6B7280),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}