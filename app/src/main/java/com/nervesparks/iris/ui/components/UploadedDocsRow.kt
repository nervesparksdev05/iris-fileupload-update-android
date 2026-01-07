package com.nervesparks.iris.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nervesparks.iris.rag.storage.LocalDoc

@Composable
fun UploadedDocsRow(
    docs: List<LocalDoc>,
    onRemove: (docId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (docs.isEmpty()) return

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(
            items = docs,
            key = { it.docId }
        ) { doc ->
            UploadedDocChip(
                name = doc.name,
                mime = doc.mime,
                status = doc.status,
                onRemove = { onRemove(doc.docId) }
            )
        }
    }
}

@Composable
private fun UploadedDocChip(
    name: String,
    mime: String,
    status: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF171E2C),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(fileTypeColor(mime)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fileTypeLabel(mime),
                    color = Color.White,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = name,
                color = Color(0xFFDDDDE4),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                modifier = Modifier.width(120.dp)
            )

            Spacer(Modifier.width(8.dp))

            StatusBadge(status)

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove document",
                    tint = Color(0xFFDDDDE4)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, bg) = when (status) {
        "READY" -> "Ready" to Color(0xFF16A34A)
        "INDEXING" -> "Indexing" to Color(0xFFF59E0B)
        "FAILED" -> "Failed" to Color(0xFFB91C1C)
        else -> status to Color(0xFF64748B)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp
        )
    }
}

private fun fileTypeLabel(mime: String): String {
    val m = mime.lowercase()
    return when {
        m.contains("pdf") -> "PDF"
        m.contains("word") || m.contains("msword") || m.contains("officedocument.wordprocessingml") -> "DOC"
        m.contains("excel") || m.contains("spreadsheet") || m.contains("officedocument.spreadsheetml") -> "XLS"
        m.startsWith("text/") || m.contains("json") || m.contains("xml") -> "TXT"
        else -> "FILE"
    }
}

private fun fileTypeColor(mime: String): Color {
    val m = mime.lowercase()
    return when {
        m.contains("pdf") -> Color(0xFFB91C1C)
        m.contains("word") || m.contains("msword") || m.contains("officedocument.wordprocessingml") -> Color(0xFF2563EB)
        m.contains("excel") || m.contains("spreadsheet") || m.contains("officedocument.spreadsheetml") -> Color(0xFF16A34A)
        m.startsWith("text/") || m.contains("json") || m.contains("xml") -> Color(0xFF6D28D9)
        else -> Color(0xFF64748B)
    }
}
