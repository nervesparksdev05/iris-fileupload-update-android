package com.nervesparks.iris.ui

import android.app.DownloadManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervesparks.iris.MainViewModel
import com.nervesparks.iris.R
import com.nervesparks.iris.ui.components.EmbeddingModelCard
import com.nervesparks.iris.ui.components.LoadingModal
import com.nervesparks.iris.ui.components.ModelCard
import java.io.File

@Composable
fun ModelsScreen(
    extFileDir: File?,
    viewModel: MainViewModel,
    onSearchResultButtonClick: () -> Unit,
    dm: DownloadManager
) {
    // Observe viewModel.refresh to trigger recomposition
    val refresh = viewModel.refresh
    if (refresh) viewModel.refresh = false

    // ✅ Filter embedding models OUT of allModels so ModelCard never gets an embedding model
    val embeddingNames: Set<String> =
        viewModel.embeddingModels.mapNotNull { it["name"]?.toString() }.toSet()

    val chatModelsOnly =
        viewModel.allModels.filter { it["name"]?.toString() !in embeddingNames }

    Box {
        if (viewModel.showAlert) {
            LoadingModal(viewModel)
        }

        LazyColumn(modifier = Modifier.padding(horizontal = 15.dp)) {
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .clickable { onSearchResultButtonClick() }
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            painter = painterResource(id = R.drawable.search_svgrepo_com__3_),
                            contentDescription = "Parameters",
                            tint = Color.White
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Search Hugging-Face Models",
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 7.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            modifier = Modifier.size(20.dp),
                            painter = painterResource(id = R.drawable.right_arrow_svgrepo_com),
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }

                    Divider(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.DarkGray,
                        thickness = 1.dp
                    )
                    Spacer(Modifier.height(25.dp))

                    // 🧠 Embedding Models Section
                    Text(
                        text = "Embedding Models",
                        color = Color.White.copy(alpha = .5f),
                        modifier = Modifier.padding(5.dp),
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Required for document processing & RAG",
                        color = Color.White.copy(alpha = .3f),
                        modifier = Modifier.padding(start = 5.dp, bottom = 8.dp),
                        fontSize = 12.sp
                    )
                }
            }

            // Embedding models (Download + Delete happens ONLY here)
            items(viewModel.embeddingModels) { model ->
                extFileDir?.let {
                    model["source"]?.let { source ->
                        EmbeddingModelCard(
                            modelName = model["name"].toString(),
                            modelSize = model["size"] ?: "~25MB",
                            modelDescription = model["description"] ?: "",
                            viewModel = viewModel,
                            dm = dm,
                            extFilesDir = extFileDir,
                            downloadLink = source
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(15.dp))
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.DarkGray,
                    thickness = 1.dp
                )
                Spacer(Modifier.height(25.dp))

                Text(
                    text = "Suggested Models",
                    color = Color.White.copy(alpha = .5f),
                    modifier = Modifier.padding(5.dp),
                    fontSize = 18.sp
                )
                Text(
                    text = "Chat/Language models for conversations",
                    color = Color.White.copy(alpha = .3f),
                    modifier = Modifier.padding(start = 5.dp, bottom = 8.dp),
                    fontSize = 12.sp
                )
            }

            // ✅ Use chatModelsOnly here (not viewModel.allModels)
            items(chatModelsOnly.take(3)) { model ->
                extFileDir?.let {
                    model["source"]?.let { source ->
                        ModelCard(
                            model["name"].toString(),
                            viewModel = viewModel,
                            dm = dm,
                            extFilesDir = extFileDir,
                            downloadLink = source,
                            showDeleteButton = true
                        )
                    }
                }
            }

            item {
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.DarkGray,
                    thickness = 1.dp
                )
            }

            // ✅ Use chatModelsOnly here too
            items(chatModelsOnly.drop(3)) { model ->
                extFileDir?.let {
                    model["source"]?.let { source ->
                        ModelCard(
                            model["name"].toString(),
                            viewModel = viewModel,
                            dm = dm,
                            extFilesDir = extFileDir,
                            downloadLink = source,
                            showDeleteButton = true
                        )
                    }
                }
            }
        }
    }
}
