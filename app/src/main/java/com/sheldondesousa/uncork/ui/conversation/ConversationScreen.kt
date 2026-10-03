package com.sheldondesousa.uncork.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.ChatResultCardBackground
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.ResultCardBackground
import com.sheldondesousa.uncork.ui.theme.Wine
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.components.BackArrowIcon
import com.sheldondesousa.uncork.ui.components.ResultSkeleton
import com.sheldondesousa.uncork.ui.components.WineResultCard
import com.sheldondesousa.uncork.model.ChatFlowText
import com.sheldondesousa.uncork.model.DebugLatencyLog
import com.sheldondesousa.uncork.BuildConfig
import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
import com.sheldondesousa.uncork.model.GemmaTimingTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val AiResponseInk = Color(0xFF27201D)

enum class AppTab(val label: String) {
    Find("Find"),
    Conversation("Chat"),
    Favorites("My List"),
}

class ConversationSessionState(
    initialMessages: List<ChatMessage> = listOf(
        ChatMessage(
            id = Long.MIN_VALUE,
            author = MessageAuthor.Assistant,
            text = ChatFlowText.MODE_CHOICE,
            quickReplies = listOf(ChatFlowText.CURIOUS_LABEL, ChatFlowText.FIND_WINE_LABEL),
        ),
    ),
) {
    val messages = mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) }
    var draft by mutableStateOf("")
    var isReplying by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var streamingText by mutableStateOf("")
    var streamingSuggestions by mutableStateOf(emptyList<WineSuggestion>())
    var streamingSourceResults by mutableStateOf(emptyList<SourceResult>())
    var streamingFollowUpText by mutableStateOf<String?>(null)
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
    title: String = "Chat",
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val filesDirectory = LocalContext.current.applicationContext.filesDir

    fun send(text: String) {
        val query = text.trim()
        if (query.isEmpty() || state.isReplying) return

        val submittedAt = SystemClock.elapsedRealtime()
        var conversationFirstTokenMs: Long? = null
        val timing = if (BuildConfig.DEBUG) GemmaTimingTrace(filesDirectory) else null

        state.messages += ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.User,
            text = query,
        )
        state.isReplying = true
        state.errorMessage = null
        state.streamingText = ""
        state.streamingSuggestions = emptyList()
        state.streamingSourceResults = emptyList()
        state.streamingFollowUpText = null

        scope.launch {
            runCatching {
                responder.replyToUpdates(query) { update ->
                    if (timing != null && conversationFirstTokenMs == null &&
                        update.isGemmaConversationOutput && update.text.isNotEmpty()
                    ) {
                        val elapsedMs = SystemClock.elapsedRealtime() - submittedAt
                        conversationFirstTokenMs = elapsedMs
                        timing.detail("operation", "chat_conversation")
                        timing.duration("user_send_to_first_token", elapsedMs)
                        scope.launch(Dispatchers.IO) { timing.save() }
                    }
                    state.streamingText = update.text
                    state.streamingSuggestions = update.suggestions
                    state.streamingSourceResults = update.sourceResults
                    // Once set (Kaggle/cache have settled), a later update with no follow-up
                    // text of its own — e.g. Gemma resolving — must not clear it back out.
                    state.streamingFollowUpText = update.followUpText ?: state.streamingFollowUpText
                }
            }
                .onSuccess { response ->
                    state.streamingText = ""
                    state.streamingSuggestions = emptyList()
                    state.streamingSourceResults = emptyList()
                    state.streamingFollowUpText = null
                    val timeToFirstWordMs = if (BuildConfig.DEBUG) {
                        DebugLatencyLog.drain()
                            .firstOrNull { (label, _) -> label.endsWith("time to first word") }
                            ?.second
                    } else {
                        null
                    }
                    state.messages += response.copy(
                        followUpText = null,
                        debugLatencyMs = conversationFirstTokenMs ?: timeToFirstWordMs,
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
                    state.streamingSourceResults = emptyList()
                    state.streamingFollowUpText = null
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
        streamingSourceResults = state.streamingSourceResults,
        streamingFollowUpText = state.streamingFollowUpText,
        onDraftChange = { state.draft = it },
        onSend = ::submit,
        onQuickReplySelected = ::send,
        onSuggestionClick = onSuggestionClick,
        onBack = onBack,
        title = title,
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
    streamingSourceResults: List<SourceResult>,
    streamingFollowUpText: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuickReplySelected: (String) -> Unit,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onBack: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)
    val hasStreamingContent = streamingText.isNotBlank() ||
        streamingSuggestions.isNotEmpty() ||
        streamingSourceResults.isNotEmpty() ||
        !streamingFollowUpText.isNullOrBlank()

    LaunchedEffect(
        messages.size, isReplying, errorMessage, streamingText,
        streamingSuggestions.size, streamingSourceResults, streamingFollowUpText,
    ) {
        val extraRows = (if (hasStreamingContent) 1 else 0) +
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
            title = title,
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
                    if (hasStreamingContent) {
                        item(key = "streaming-response") {
                            MessageBubble(
                                message = ChatMessage(
                                    id = Long.MIN_VALUE,
                                    author = MessageAuthor.Assistant,
                                    text = streamingText,
                                    suggestions = streamingSuggestions,
                                    sourceResults = streamingSourceResults,
                                ),
                                // Kaggle/cache cards can be on screen well before Gemma (or the
                                // whole turn) finishes — they must be clickable as soon as
                                // they're shown, not only once streaming ends.
                                onSuggestionClick = onSuggestionClick,
                            )
                        }
                        // Always its own bubble, never folded into the cards' bubble above —
                        // matches how it renders once the turn actually finishes (a separate
                        // follow-up message), instead of briefly appearing inside the response
                        // bubble's own background while cards are still streaming in.
                        if (!streamingFollowUpText.isNullOrBlank()) {
                            item(key = "streaming-follow-up") {
                                MessageBubble(
                                    message = ChatMessage(
                                        id = Long.MIN_VALUE + 1,
                                        author = MessageAuthor.Assistant,
                                        text = streamingFollowUpText,
                                    ),
                                    onSuggestionClick = {},
                                )
                            }
                        }
                    }
                    if (isReplying && !hasStreamingContent) {
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
    val hasWineResults = message.sourceResults.isNotEmpty() || message.wineSuggestions.isNotEmpty()
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
                modifier = if (hasWineResults) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth(0.92f)
                        .height(IntrinsicSize.Min)
                },
            ) {
                if (!hasWineResults) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Ink.copy(alpha = 0.50f)),
                    )
                }
                Column(
                    modifier = if (hasWineResults) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                            .padding(start = 18.dp, top = 4.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(ChatResultCardBackground)
                            .padding(horizontal = 14.dp, vertical = 16.dp)
                    },
                ) {
                    // A search-type response has no top-of-bubble text (each master card below
                    // carries its own) — skip the Text entirely rather than reserving a full
                    // empty line's height for it, which was the source of the large gap above
                    // the first card.
                    if (message.text.isNotBlank()) {
                        Text(
                            text = parseBoldMarkdown(message.text),
                            color = AiResponseInk,
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                        )
                    }
                    message.debugLatencyMs?.let { timeToFirstWordMs ->
                        Text(
                            text = "⏱ time to first word: %.1fs".format(timeToFirstWordMs / 1000f),
                            color = InkSubtle,
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
                    // Only add a gap above the first card when something actually precedes it —
                    // when the bubble has no top text (the normal case for a search response),
                    // the bubble's own 16dp top padding is already the gap; stacking another 16dp
                    // spacer on top of that was the large empty space above the first card.
                    val hasLeadingContent = message.text.isNotBlank() ||
                        (message.quickReplies.isNotEmpty() && quickRepliesEnabled)
                    if (message.sourceResults.isNotEmpty()) {
                        // One master card per search type, always in the fixed display order
                        // (AI Sommelier, Reviews, Extended db, Web Search) regardless of which
                        // resolved first — a card still in flight shows a loader, and one that
                        // resolved with nothing says so, rather than the search silently vanishing.
                        if (hasLeadingContent) Spacer(Modifier.height(16.dp))
                        message.sourceResults.sortedBy { it.source.ordinal }.forEachIndexed { groupIndex, sourceResult ->
                            if (groupIndex > 0) SearchTypeDivider()
                            SourceResultCard(
                                sourceResult = sourceResult,
                                onSuggestionClick = onSuggestionClick,
                                cardBackground = ChatResultCardBackground,
                                onRetry = if (quickRepliesEnabled) {
                                    { onQuickReplySelected("Try Again") }
                                } else {
                                    null
                                },
                            )
                        }
                    } else {
                        val suggestions = message.wineSuggestions
                        if (suggestions.isNotEmpty()) {
                            if (hasLeadingContent) Spacer(Modifier.height(16.dp))
                            // One card per source group — a card's individual pill was replaced
                            // by a single response-level card, since every card in a given
                            // response already comes from the same source.
                            val sourceGroups = suggestions.groupBy { it.source }.toSortedMap()
                            sourceGroups.entries.forEachIndexed { groupIndex, (source, groupSuggestions) ->
                                if (groupIndex > 0) SearchTypeDivider()
                                SourceResultCard(
                                    sourceResult = SourceResult(
                                        source = source,
                                        status = SourceQueryStatus.COMPLETE,
                                        suggestions = groupSuggestions,
                                    ),
                                    onSuggestionClick = onSuggestionClick,
                                    cardBackground = ChatResultCardBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// **bold** renders bold black; ##heading## renders burgundy (used for Q3's taste characteristics);
// %%heading%% renders burgundy bold (used for the Ask welcome's "I can help with:").
private fun parseBoldMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val delimiters = listOf("**", "##", "%%")
    var cursor = 0

    while (cursor < source.length) {
        val opening = delimiters
            .mapNotNull { delimiter -> source.indexOf(delimiter, cursor).takeIf { it != -1 }?.let { it to delimiter } }
            .minByOrNull { it.first }
        if (opening == null) {
            append(source.substring(cursor))
            break
        }
        val (marker, delimiter) = opening

        val closing = source.indexOf(delimiter, marker + 2)
        if (closing == -1) {
            append(source.substring(cursor))
            break
        }

        append(source.substring(cursor, marker))
        val style = when (delimiter) {
            "**" -> SpanStyle(fontWeight = FontWeight.Bold)
            "%%" -> SpanStyle(color = Wine, fontWeight = FontWeight.Bold)
            else -> SpanStyle(color = Wine, fontWeight = FontWeight.Normal)
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

/** A line separator between two consecutive search-type blocks (Kaggle, Cache, Gemma, …). */
@Composable
private fun SearchTypeDivider() {
    Spacer(Modifier.height(20.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(Wine),
    )
    Spacer(Modifier.height(20.dp))
}

/**
 * One search type's own result block: its label and status text sit outside any border — only
 * the individual wine cards below them are containerized, each in its own bordered box, rather
 * than one shared border wrapping the label, text, and every card together.
 */
@Composable
internal fun SourceResultCard(
    sourceResult: SourceResult,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onRetry: (() -> Unit)? = null,
    cardBackground: Color = ResultCardBackground,
) {
    val displayedSuggestions = sourceResult.suggestions.filter { it.name.isUsefulCardValue() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceLabel(sourceResult.source)
            if (sourceResult.status == SourceQueryStatus.LOADING) {
                ThreeDotsLoadingIndicator(dotSize = 6.dp, spacing = 4.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            sourceResult.status == SourceQueryStatus.LOADING -> {
                if (sourceResult.source == WineSuggestionSource.GEMMA) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        displayedSuggestions.forEach { suggestion ->
                            SuggestionCard(
                                suggestion = suggestion,
                                onClick = { onSuggestionClick(suggestion) },
                                background = cardBackground,
                            )
                        }
                        repeat((3 - displayedSuggestions.size).coerceAtLeast(0)) { index ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(cardBackground, RoundedCornerShape(10.dp))
                                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Wine,
                                    strokeWidth = 1.5.dp,
                                )
                                Text(
                                    text = "Preparing wine ${displayedSuggestions.size + index + 1}…",
                                    color = InkSubtle,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                } else {
                    ResultSkeleton()
                }
            }
            // A genuine failure (network error, or the request was interrupted, e.g. the user
            // switched away mid-search) — distinct from a search that ran fine and found
            // nothing. Offers a retry instead of quietly falling through to some other turn.
            sourceResult.status == SourceQueryStatus.FAILED -> {
                Text(
                    text = "${sourceResult.source.label()} could not be completed",
                    color = InkSubtle,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Try Again",
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Wine.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Wine,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            displayedSuggestions.isEmpty() -> Text(
                text = "No results found",
                color = InkSubtle,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
            )
            else -> {
                Text(
                    text = "Here are a few options from ${sourceResult.source.introLabel()}",
                    color = InkSubtle,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    displayedSuggestions.forEach { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            onClick = { onSuggestionClick(suggestion) },
                            background = cardBackground,
                        )
                    }
                }
            }
        }
    }
}

/** A single wine suggestion, containerized in its own bordered card. */
@Composable
private fun SuggestionCard(
    suggestion: WineSuggestion,
    onClick: () -> Unit,
    background: Color,
) {
    WineResultCard(wine = suggestion, onClick = onClick, containerColor = background)
}

/**
 * A single label at the top of a search-type card, distinguishing reviewed wines, cache, AI, and
 * Web Search — the same distinction Find surfaces via its "AI Sommelier" vs "Database" sections.
 * Plain text, no chip background, so it reads as a heading rather than a tappable pill.
 */
@Composable
private fun SourceLabel(source: WineSuggestionSource) {
    Text(
        text = source.label(),
        modifier = Modifier.semantics { heading() },
        color = Wine,
        fontSize = 20.sp,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
    )
}

private fun WineSuggestionSource.label(): String = when (this) {
    WineSuggestionSource.GEMMA -> "AI Sommelier"
    WineSuggestionSource.KAGGLE -> "Reviewed Wines"
    WineSuggestionSource.CACHE -> "Extended db"
    WineSuggestionSource.WEB_SEARCH -> "Web Search"
}

// The fuller name used only in the "Here are a few options from …" body line — the header tag
// itself stays concise, while the intro can use a fuller source description.
private fun WineSuggestionSource.introLabel(): String = when (this) {
    WineSuggestionSource.GEMMA -> "Gemma Knowledge"
    else -> label()
}

/** Three dots pulsing in sequence, matching Find's "AI Sommelier" loading indicator. */
@Composable
private fun ThreeDotsLoadingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = Wine,
    dotSize: Dp = 10.dp,
    spacing: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "loading-dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 150, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index-scale",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

internal fun String.isUsefulCardValue(): Boolean =
    isNotBlank() && !equals("Unknown", ignoreCase = true)

internal fun String.cardValueOrUnknown(): String =
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
            color = InkSubtle,
            strokeWidth = 1.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Thinking…",
            color = InkSubtle,
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
            // Lift the box clear of the system gesture/navigation bar (the screen draws edge to edge),
            // then leave a little breathing room below it. When the keyboard is open, imePadding on the
            // screen has already consumed that space, so this adds nothing extra.
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp)
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
                            color = InkSubtle,
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
                tint = if (canSend) Parchment else InkSubtle,
            )
        }
    }
}
