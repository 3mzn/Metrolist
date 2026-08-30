/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R

/**
 * The floating chat bubble (SPEC_LT_CHAT): partner initials avatar with an online-style
 * indicator, and an unread badge (top-right) fed by [unreadCount]. Sits bottom-end on the
 * Listen Together tab, above the mini player (padding handled by the caller).
 */
@Composable
fun ChatBubble(
    partnerInitial: String,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    partnerName: String = partnerInitial,
    isOnline: Boolean = true,
) {
    val displayInitial = partnerInitial.ifEmpty { "?" }
    val openDesc = stringResource(R.string.lt_chat_open, partnerName)
    Box(modifier = modifier.size(56.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.size(56.dp).semantics { contentDescription = openDesc },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                Text(
                    text = displayInitial,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Online-style indicator — only when partner seen within 2 min (freshness).
        if (isOnline) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .size(14.dp),
            ) {}
        }

        if (unreadCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
