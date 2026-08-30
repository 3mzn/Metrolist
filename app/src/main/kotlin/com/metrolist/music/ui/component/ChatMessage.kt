/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ltchat.LtChatMessage

/** Swipe-right reply gesture threshold. */
private const val SWIPE_REPLY_THRESHOLD_DP = 72

/**
 * One chat message row (SPEC_LT_CHAT): incoming = left with partner avatar, outgoing = right.
 * Both gesture paths to reply are wired — long-press AND swipe-right (drag accumulates, fires
 * at [SWIPE_REPLY_THRESHOLD_DP], then resets).
 *
 * Theming: [bubbleColor] comes from the song palette (lighter shade incoming, darker
 * outgoing); [onBubbleColor] is derived for contrast. Quote preview renders from the
 * denormalized [message] fields, with [replyTargetText] as the live lookup override.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: LtChatMessage,
    isMine: Boolean,
    partnerInitial: String,
    bubbleColor: Color,
    onBubbleColor: Color,
    timeText: String,
    isRead: Boolean,
    replyTargetText: String?,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val swipeReplyThreshold = with(density) { SWIPE_REPLY_THRESHOLD_DP.dp.toPx() }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!isMine) {
            PartnerChatAvatar(partnerInitial)
            Spacer(Modifier.padding(start = 6.dp))
        }

        MessageColumn(
            message = message,
            isMine = isMine,
            bubbleColor = bubbleColor,
            onBubbleColor = onBubbleColor,
            timeText = timeText,
            isRead = isRead,
            replyTargetText = replyTargetText,
            onReply = onReply,
            swipeReplyThreshold = swipeReplyThreshold,
        )
    }
}

/**
 * The draggable bubble stack: quote preview + bubble + timestamp/read row. Kept as a separate
 * composable so the swipe gesture state stays local and predictable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageColumn(
    message: LtChatMessage,
    isMine: Boolean,
    bubbleColor: Color,
    onBubbleColor: Color,
    timeText: String,
    isRead: Boolean,
    replyTargetText: String?,
    onReply: () -> Unit,
    swipeReplyThreshold: Float,
) {
    val density = LocalDensity.current
    var dragX by remember { mutableFloatStateOf(0f) }

    Column(
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        modifier =
            Modifier
                .widthIn(max = 280.dp)
                .offset(x = with(density) { dragX.toDp() })
                .combinedClickable(onClick = {}, onLongClick = onReply)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragX >= swipeReplyThreshold) onReply()
                            dragX = 0f
                        },
                        onDragCancel = {
                            dragX = 0f
                        },
                    ) { _, dragAmount ->
                        // Only rightward drags accumulate, so list scrolling is unaffected.
                        dragX = (dragX + dragAmount).coerceAtLeast(0f)
                    }
                },
    ) {
        val quoted = replyTargetText ?: message.replyText
        if (message.replyTo != null && quoted != null) {
            QuotedReplyPreview(
                senderName = message.replySenderName ?: "",
                text = quoted,
                accentColor = if (isMine) onBubbleColor else bubbleColor,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }

        Surface(
            color = bubbleColor,
            shape =
                RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
            shadowElevation = 1.dp,
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = onBubbleColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isMine && isRead) {
                Spacer(Modifier.padding(start = 3.dp))
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = bubbleColor,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** Initials avatar for the partner, matching the LT screen's UserAvatar circles. */
@Composable
private fun PartnerChatAvatar(initial: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondary,
            )
        }
    }
}

/** Lighter palette shade for incoming bubbles — adaptive to theme via surface blend. */
@Composable
fun lightBubbleColor(base: Color): Color {
    val target = MaterialTheme.colorScheme.surfaceContainerHighest
    // Blend toward surface for adaptive contrast in both light/dark themes
    return lerp(base, target, 0.35f).let { lerp(it, Color.White, 0.15f) }
}

/** Darker palette shade for outgoing bubbles — adaptive. */
@Composable
fun darkBubbleColor(base: Color): Color {
    val target = MaterialTheme.colorScheme.surfaceContainerLowest
    return lerp(base, target, 0.25f).let { lerp(it, Color.Black, 0.20f) }
}

/** White/black text chosen by bubble luminance so both shades stay readable. */
fun onBubbleColor(bubble: Color): Color =
    if (0.2126f * bubble.red + 0.7152f * bubble.green + 0.0722f * bubble.blue > 0.5f) {
        Color.Black
    } else {
        Color.White
    }
