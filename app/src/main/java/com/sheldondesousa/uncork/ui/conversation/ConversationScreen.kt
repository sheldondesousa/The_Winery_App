package com.sheldondesousa.uncork.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.components.BackArrowIcon
import com.sheldondesousa.uncork.model.ChatFlowText
import com.sheldondesousa.uncork.model.DebugLatencyLog
import com.sheldondesousa.uncork.BuildConfig
import kotlinx.coroutines.launch

private val AiResponseInk = Color(0xFF27201D)

enum class AppTab(val label: String) {
    Find("Find"),
    Conversation("Chat"),
    Favorites("My List"),
}

class ConversationSessionState {
    val messages = mutableStateListOf(
        ChatMessage(
            id = Long.MIN_VALUE,
            author = MessageAuthor.Assistant,
            text = ChatFlowText.MODE_CHOICE,
            quickReplies = listOf(ChatFlowText.CURIOUS_LABEL, ChatFlowText.FIND_WINE_LABEL),
        ),
    )
    var draft by mutableStateOf("")
    var isReplying by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var streamingText by mutableStateOf("")
    var streamingSuggestions by mutableStateOf(emptyList<WineSuggestion>())
}

@Composable
fun rememberConversationSessionState(): ConversationSessionState =
    remember { ConversationSessionState() }

@Composable
fun ConversationRoute(
    responder: ConversationResponder = remember { DemoConversationResponder() },
    state: ConversationSessionState = rememberConversationSessionState(),
    onSuggestionClick: (WineSuggestion) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun send(text: String) {
        val query = text.trim()
        if (query.isEmpty() || state.isReplying) return

        state.messages += ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.User,
            text = query,
        )
        state.isReplying = true
        state.errorMessage = null
        state.streamingText = ""
        state.streamingSuggestions = emptyList()

        scope.launch {
            runCatching {
                responder.replyToUpdates(query) { update ->
                    state.streamingText = update.text
                    state.streamingSuggestions = update.suggestions
                }
            }
                .onSuccess { response ->
                    state.streamingText = ""
                    state.streamingSuggestions = emptyList()
                    val timeToFirstWordMs = if (BuildConfig.DEBUG) {
                        DebugLatencyLog.drain()
                            .firstOrNull { (label, _) -> label.endsWith("time to first word") }
                            ?.second
                    } else {
                        null
                    }
                    state.messages += response.copy(
                        followUpText = null,
                        debugLatencyMs = timeToFirstWordMs,
                    )
                    response.followUpText
                        ?.takeIf { it.isNotBlank() }
                        ?.let { followUp ->
                            state.messages += ChatMessage(
                                id = System.nanoTime(),
                                author = MessageAuthor.Assistant,
                                text = followUp,
                                historyRequest = response.historyRequest,
                            )
                        }
                }
                .onFailure {
                    state.streamingText = ""
                    state.streamingSuggestions = emptyList()
                    state.errorMessage =
                        "I couldn’t finish that suggestion. Check your connection and try again."
                }
            state.isReplying = false
        }
    }

    fun submit() {
        val query = state.draft
        state.draft = ""
        send(query)
    }

    ConversationScreen(
        messages = state.messages,
        draft = state.draft,
        isReplying = state.isReplying,
        errorMessage = state.errorMessage,
        streamingText = state.streamingText,
        streamingSuggestions = state.streamingSuggestions,
        onDraftChange = { state.draft = it },
        onSend = ::submit,
        onQuickReplySelected = ::send,
        onSuggestionClick = onSuggestionClick,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun ConversationScreen(
    messages: List<ChatMessage>,
    draft: String,
    isReplying: Boolean,
    errorMessage: String?,
    streamingText: String,
    streamingSuggestions: List<WineSuggestion>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuickReplySelected: (String) -> Unit,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)

    LaunchedEffect(messages.size, isReplying, errorMessage, streamingText, streamingSuggestions.size) {
        val extraRows = (if (streamingText.isNotBlank() || streamingSuggestions.isNotEmpty()) 1 else 0) +
            (if (isReplying) 1 else 0) +
            (if (errorMessage != null) 1 else 0)
        val finalIndex = messages.lastIndex + extraRows
        if (finalIndex >= 0) listState.animateScrollToItem(finalIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding()
            .imePadding(),
    ) {
        AppHeader(
            title = "Chat",
            icon = BackArrowIcon,
            onIconClick = onBack,
            iconContentDescription = "Back",
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (messages.isEmpty() && !isReplying && errorMessage == null) {
                EmptyConversation()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        top = 24.dp,
                        end = 20.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        MessageBubble(
                            message = message,
                            onSuggestionClick = onSuggestionClick,
                            quickRepliesEnabled = index == messages.lastIndex && !isReplying,
                            onQuickReplySelected = onQuickReplySelected,
                        )
                    }
                    if (streamingText.isNotBlank() || streamingSuggestions.isNotEmpty()) {
                        item(key = "streaming-response") {
                            MessageBubble(
                                message = ChatMessage(
                                    id = Long.MIN_VALUE,
                                    author = MessageAuthor.Assistant,
                                    text = streamingText,
                                    suggestions = streamingSuggestions,
                                ),
                                onSuggestionClick = {},
                            )
                        }
                    }
                    if (isReplying) {
                        item(key = "replying") { ReplyingIndicator() }
                    }
                    if (errorMessage != null) {
                        item(key = "error") { ErrorBubble(errorMessage) }
                    }
                }
            }
        }

        MessageComposer(
            value = draft,
            enabled = !isReplying,
            onValueChange = onDraftChange,
            onSend = onSend,
        )
    }
}

@Composable
private fun EmptyConversation() {
    Spacer(modifier = Modifier.fillMaxSize())
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onSuggestionClick: (WineSuggestion) -> Unit,
    quickRepliesEnabled: Boolean = false,
    onQuickReplySelected: (String) -> Unit = {},
) {
    val isUser = message.author == MessageAuthor.User
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (isUser) "You said: ${message.text}" else "Assistant said: ${message.text}"
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Text(
                text = message.text,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Wine.copy(alpha = 0.08f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                color = Wine,
                fontSize = 16.sp,
                lineHeight = 26.sp,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Ink.copy(alpha = 0.50f)),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 18.dp, top = 4.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = parseBoldMarkdown(message.text),
                        color = AiResponseInk,
                        fontSize = 16.sp,
                        lineHeight = 28.sp,
                    )
                    message.debugLatencyMs?.let { timeToFirstWordMs ->
                        Text(
                            text = "⏱ time to first word: %.1fs".format(timeToFirstWordMs / 1000f),
                            color = InkMuted,
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (message.quickReplies.isNotEmpty() && quickRepliesEnabled) {
                        Spacer(Modifier.height(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            message.quickReplies.forEach { label ->
                                QuickReplyButton(
                                    label = label,
                                    onClick = { onQuickReplySelected(label) },
                                )
                            }
                        }
                    }
                    val suggestions = message.wineSuggestions
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        suggestions.forEachIndexed { index, suggestion ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Hairline),
                                )
                            }
                            SuggestionLink(
                                suggestion = suggestion,
                                onClick = { onSuggestionClick(suggestion) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// **bold** renders bold black; ##heading## renders burgundy (used for Q3's taste characteristics).
private fun parseBoldMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0

    while (cursor < source.length) {
        val boldOpening = source.indexOf("**", cursor).takeIf { it != -1 }
        val headingOpening = source.indexOf("##", cursor).takeIf { it != -1 }
        val marker = when {
            boldOpening == null -> headingOpening
            headingOpening == null -> boldOpening
            else -> minOf(boldOpening, headingOpening)
        }
        if (marker == null) {
            append(source.substring(cursor))
            break
        }
        val delimiter = source.substring(marker, marker + 2)

        val closing = source.indexOf(delimiter, marker + 2)
        if (closing == -1) {
            append(source.substring(cursor))
            break
        }

        append(source.substring(cursor, marker))
        val style = if (delimiter == "**") {
            SpanStyle(fontWeight = FontWeight.Bold)
        } else {
            SpanStyle(color = Wine, fontWeight = FontWeight.Normal)
        }
        withStyle(style) {
            append(source.substring(marker + 2, closing))
        }
        cursor = closing + 2
    }
}

@Composable
private fun QuickReplyButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Wine.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = Wine,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

private val LeftRuleShape = RoundedCornerShape(
    topStart = 1.dp,
    topEnd = 0.dp,
    bottomEnd = 0.dp,
    bottomStart = 1.dp,
)

@Composable
private fun SuggestionLink(suggestion: WineSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name.ifBlank { "Unknown" },
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            suggestion.winery
                .takeIf { it.isUsefulCardValue() }
                ?.let { winery ->
                    Text(
                        text = winery,
                        color = InkMuted,
                        fontSize = 13.sp,
                        letterSpacing = 0.3.sp,
                    )
                }
            Text(
                text = listOf(suggestion.country, suggestion.province)
                    .joinToString(", ") { it.cardValueOrUnknown() },
                modifier = Modifier.padding(top = 4.dp),
                color = Wine,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
            )
            if (suggestion.isFavorite) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = suggestion.favoriteRating?.let { "$it / 10" } ?: "Favorited",
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Wine.copy(alpha = 0.10f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    color = Wine,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = "›",
            color = InkMuted,
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

private fun String.isUsefulCardValue(): Boolean =
    isNotBlank() && !equals("Unknown", ignoreCase = true)

private fun String.cardValueOrUnknown(): String =
    takeIf { it.isUsefulCardValue() } ?: "Unknown"

@Composable
private fun ReplyingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = InkMuted,
            strokeWidth = 1.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Thinking…",
            color = InkMuted,
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun ErrorBubble(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Hairline),
        )
        Text(
            text = message,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 8.dp),
            color = Wine,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
    }
}

@Composable
private fun MessageComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val canSend = enabled && value.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .border(2.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .testTag("message-input"),
            enabled = enabled,
            textStyle = TextStyle(
                color = Ink,
                fontSize = 17.sp,
                lineHeight = 23.sp,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (canSend) {
                    onSend()
                    focusManager.clearFocus()
                }
            }),
            singleLine = false,
            maxLines = 4,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(vertical = 7.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Ask me about wine",
                            color = InkMuted,
                            fontSize = 17.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(if (canSend) Wine else Hairline)
                .clickable(enabled = canSend, role = Role.Button) {
                    onSend()
                    focusManager.clearFocus()
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "Send message"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (canSend) Parchment else InkMuted,
            )
        }
    }
}

