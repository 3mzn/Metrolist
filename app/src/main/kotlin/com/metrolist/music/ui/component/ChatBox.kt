/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ltchat.LtChatMessage
import com.metrolist.music.ltchat.LtChatViewModel
import com.metrolist.music.ui.theme.PlayerColorExtractor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When the user is at/below this many visible items from the newest message (index 0 in the
 * reverse layout), an auto-scroll down to the newest message is considered unobtrusive. Scrolled
 * up further into history = reading; we don't yank them back.
 */
private const val NEAR_BOTTOM_INDEX_THRESHOLD = 2

/**
 * The expanded chat panel (SPEC_LT_CHAT): partner header + typing indicator, message list
 * (newest at bottom via reverseLayout), quoted-reply preview and input row. Bubbles are tinted
 * from the CURRENT SONG's palette — same extraction pipeline as the player (Coil 100x100 →
 * Palette → PlayerColorExtractor), cached per song; falls back to Material colors otherwise.
 */
@Composable
fun ChatBox(
    viewModel: LtChatViewModel,
    partnerName: String,
    partnerInitial: String,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val partnerPresence by viewModel.partnerPresence.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val dateTimeFormatter = remember { DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault()) }
    val startOfDayMs = remember {
        LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    var input by rememberSaveable { mutableStateOf("") }
    var replying by remember { mutableStateOf<LtChatMessage?>(null) }

    // --- Song-palette extraction (mirrors Player.kt ~406-463) ---
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value
    var paletteColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    val paletteCache = remember { LinkedHashMap<String, List<Color>>(20, 0.75f, true) }

    LaunchedEffect(mediaMetadata?.id) {
        val songId = mediaMetadata?.id
        val thumbnailUrl = mediaMetadata?.thumbnailUrl
        if (songId == null || thumbnailUrl == null) {
            paletteColors = emptyList()
            return@LaunchedEffect
        }
        paletteCache[songId]?.let { cached ->
            paletteColors = cached
            return@LaunchedEffect
        }
        val bitmap =
            withContext(Dispatchers.IO) {
                val request =
                    ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .size(100, 100)
                        .allowHardware(false)
                        .build()
                runCatching { context.imageLoader.execute(request).image?.toBitmap() }.getOrNull()
            } ?: return@LaunchedEffect
        val palette =
            withContext(Dispatchers.Default) {
                Palette.from(bitmap).maximumColorCount(8).resizeBitmapArea(100 * 100).generate()
            }
        val colors =
            PlayerColorExtractor.extractGradientColors(
                palette = palette,
                fallbackColor = 0xFF000000.toInt(),
            )
        paletteCache[songId] = colors
        if (paletteCache.size > 20) {
            val eldest = paletteCache.keys.firstOrNull()
            if (eldest != null) paletteCache.remove(eldest)
        }
        paletteColors = colors
    }

    val baseColor = paletteColors.firstOrNull()
    val incomingColor = baseColor?.let { lightBubbleColor(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    val outgoingColor = baseColor?.let { darkBubbleColor(it) } ?: MaterialTheme.colorScheme.primaryContainer
    val onIncomingColor = baseColor?.let { onBubbleColor(lightBubbleColor(it)) } ?: MaterialTheme.colorScheme.onSecondaryContainer
    val onOutgoingColor = baseColor?.let { onBubbleColor(darkBubbleColor(it)) } ?: MaterialTheme.colorScheme.onPrimaryContainer

    // Open = read. Also re-marks whenever new messages land while the panel is visible.
    LaunchedEffect(Unit) {
        viewModel.markChatOpened()
        viewModel.pruneOldMessages()
    }
    LaunchedEffect(messages.size) {
        viewModel.markChatOpened()
    }

    val messagesById = remember(messages) { messages.associateBy { it.id } }

    // --- Auto-scroll: the repository emits messages NEWEST FIRST and the list uses
    // reverseLayout, so the newest message is always index 0 anchored at the bottom.
    // On first open we jump straight to the newest message; afterwards, whenever a new
    // message arrives while we're already near the bottom, we glide to it. If the user has
    // scrolled up into history we leave their reading position alone.
    val messageListState = rememberLazyListState()
    var initialScrollDone by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size, messages.firstOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            // First non-empty frame: land on the newest message without animating through history.
            initialScrollDone = true
            messageListState.scrollToItem(0)
        } else if (messageListState.firstVisibleItemIndex <= NEAR_BOTTOM_INDEX_THRESHOLD) {
            // New message while the user is at/near the bottom → scroll it into view.
            messageListState.animateScrollToItem(0)
        }
    }

    val panelHeight = (configuration.screenHeightDp * 0.7f).dp

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(panelHeight)
                .imePadding(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: partner avatar + name + typing indicator + collapse button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                        Text(
                            text = partnerInitial,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary,
                        )
                    }
                }
                Spacer(Modifier.padding(start = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = partnerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val isTypingFresh = partnerPresence?.let {
                        it.isTyping && System.currentTimeMillis() - it.lastSeenMs < 10_000
                    } == true
                    if (isTypingFresh) {
                        Text(
                            text = stringResource(R.string.lt_chat_typing, partnerName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onCollapse) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.lt_chat_collapse),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Message list — NEWEST FIRST from the repository, so reverseLayout renders it at
            // the bottom with correct anchoring and free scrolling through history.
            LazyColumn(
                state = messageListState,
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.lt_chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                }
                items(messages, key = { it.id.ifEmpty { "pending_${it.text.hashCode()}_${it.senderUid}_${it.createdAtMs}" } }) { message ->
                    val isMine = message.senderUid == myUid
                    ChatMessageItem(
                        message = message,
                        isMine = isMine,
                        partnerInitial = partnerInitial,
                        bubbleColor = if (isMine) outgoingColor else incomingColor,
                        onBubbleColor = if (isMine) onOutgoingColor else onIncomingColor,
                        timeText =
                            if (message.createdAtMs <= 0L) {
                                "…"
                            } else if (message.createdAtMs < startOfDayMs) {
                                dateTimeFormatter.format(
                                    Instant.ofEpochMilli(message.createdAtMs).atZone(ZoneId.systemDefault()),
                                )
                            } else {
                                timeFormatter.format(
                                    Instant.ofEpochMilli(message.createdAtMs).atZone(ZoneId.systemDefault()),
                                )
                            },
                        isRead = isMine && viewModel.isMessageRead(message),
                        replyTargetText = message.replyTo?.let { messagesById[it]?.text },
                        onReply = { replying = message },
                    )
                }
            }

            // Reply preview (what we're about to reply to).
            replying?.let { target ->
                QuotedReplyPreview(
                    senderName = target.senderName,
                    text = target.text,
                    accentColor = incomingColor,
                    onDismiss = { replying = null },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                )
            }

            // Input row — free text incl. Unicode emoji (system keyboard), typing hook wired.
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        viewModel.onInputChanged()
                    },
                    placeholder = { Text(stringResource(R.string.lt_chat_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (input.isNotBlank()) {
                                viewModel.send(input, replying)
                                input = ""
                                replying = null
                            }
                        }
                    ),
                )
                Spacer(Modifier.padding(start = 8.dp))
                FilledIconButton(
                    onClick = {
                        viewModel.send(input, replying)
                        input = ""
                        replying = null
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.telegram),
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
