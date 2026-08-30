/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

/**
 * The quoted-reply preview (SPEC_LT_CHAT): a small accent-bar + sender + snippet block. Used
 * in two places — inside a message bubble (shows what that message replies to) and above the
 * input field (shows what the user is ABOUT to reply to; [onDismiss] set enables the X).
 */
@Composable
fun QuotedReplyPreview(
    senderName: String,
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .height(IntrinsicSize.Min)
                    .padding(end = if (onDismiss != null) 0.dp else 10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = senderName.take(120),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = text.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}
