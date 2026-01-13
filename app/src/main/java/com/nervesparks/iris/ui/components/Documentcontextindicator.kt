package com.nervesparks.iris.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ✅ Shows indicator when response is based on uploaded documents
 * Display this above the assistant's response in chat
 */
@Composable
fun DocumentContextIndicator(
    isVisible: Boolean,
    documentNames: List<String>,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible && documentNames.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E3A5F))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = "Document",
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(14.dp)
            )

            Spacer(Modifier.width(6.dp))

            Text(
                text = "Based on: ${documentNames.joinToString(", ")}",
                color = Color(0xFF64B5F6),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * ✅ Shows chat mode indicator (Normal vs Document)
 * Display this at the top of the chat or input area
 */
@Composable
fun ChatModeIndicator(
    hasDocuments: Boolean,
    readyCount: Int,
    indexingCount: Int,
    modifier: Modifier = Modifier
) {
    val (text, bgColor, textColor) = when {
        indexingCount > 0 -> Triple(
            "📄 Indexing $indexingCount document(s)...",
            Color(0xFF3A2A1A),
            Color(0xFFF59E0B)
        )
        readyCount > 0 -> Triple(
            "📄 $readyCount document(s) ready • Ask about them!",
            Color(0xFF1B4332),
            Color(0xFF4CAF50)
        )
        else -> Triple(
            "💬 Normal chat mode",
            Color(0xFF1E1E2E),
            Color(0xFF9CA3AF)
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}