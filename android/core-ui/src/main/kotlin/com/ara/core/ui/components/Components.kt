package com.ara.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deterministic per-ID avatar color so contacts are visually stable. */
fun avatarColorFor(id: String): Color {
    val palette = listOf(
        Color(0xFF4F46E5), Color(0xFF0EA5E9), Color(0xFF14B8A6), Color(0xFFF59E0B),
        Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF10B981),
    )
    return palette[(id.hashCode().let { if (it < 0) -it else it }) % palette.size]
}

@Composable
fun Avatar(name: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val initials = name.split(" ", "-", "_", ".")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .background(avatarColorFor(name), CircleShape)
            .semantics { contentDescription = "Avatar for $name" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value / 2.6f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics { contentDescription = "$count unread messages" },
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
