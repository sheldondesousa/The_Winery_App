package com.sheldondesousa.uncork.ui.conversation

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.R
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine
import kotlinx.coroutines.launch

private val AiResponseInk = Color(0xFF27201D)

private enum class AppTab(val label: String) {
    Conversation("Chat"),
    History("History"),
    Favorites("Favorites"),
}

@Composable
fun ConversationRoute(
    responder: ConversationResponder = remember { DemoConversationResponder() },
    onSuggestionClick: (WineSuggestion) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by rememberSaveable { mutableStateOf("") }
    var isReplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val query = draft.trim()
        if (query.isEmpty() || isReplying) return

        messages += ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.User,
            text = query,
        )
        draft = ""
        isReplying = true
        errorMessage = null

        scope.launch {
            runCatching { responder.replyTo(query) }
                .onSuccess { messages += it }
                .onFailure {
                    errorMessage = "I couldn’t finish that suggestion. Check your connection and try again."
                }
            isReplying = false
        }
    }

    ConversationScreen(
        messages = messages,
        draft = draft,
        isReplying = isReplying,
        errorMessage = errorMessage,
        onDraftChange = { draft = it },
        onSend = ::submit,
        onSuggestionClick = onSuggestionClick,
        modifier = modifier,
    )
}

@Composable
private fun ConversationScreen(
    messages: List<ChatMessage>,
    draft: String,
    isReplying: Boolean,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestionClick: (WineSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isReplying, errorMessage) {
        val extraRows = (if (isReplying) 1 else 0) + (if (errorMessage != null) 1 else 0)
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
        ConversationHeader()

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
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onSuggestionClick = onSuggestionClick,
                        )
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
        BottomNavigation(selected = AppTab.Conversation)
    }
}

@Composable
private fun ConversationHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Uncork",
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "AI SOMMELIER",
            modifier = Modifier.padding(top = 1.dp),
            color = Wine,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun EmptyConversation() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(240.dp)) {
            Image(
                painter = painterResource(R.drawable.wine_glass),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "Uncork",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 89.dp, y = 139.dp),
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onSuggestionClick: (WineSuggestion) -> Unit,
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
                    message.suggestion?.let { suggestion ->
                        Spacer(Modifier.height(16.dp))
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

private fun parseBoldMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0

    while (cursor < source.length) {
        val opening = source.indexOf("**", cursor)
        if (opening == -1) {
            append(source.substring(cursor))
            break
        }

        val closing = source.indexOf("**", opening + 2)
        if (closing == -1) {
            append(source.substring(cursor))
            break
        }

        append(source.substring(cursor, opening))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(source.substring(opening + 2, closing))
        }
        cursor = closing + 2
    }
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
                text = suggestion.name,
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = suggestion.region,
                color = InkMuted,
                fontSize = 13.sp,
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

@Composable
private fun BottomNavigation(selected: AppTab) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Wine)
            .navigationBarsPadding()
            .height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationItem(
            label = AppTab.Conversation.label,
            selected = selected == AppTab.Conversation,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
        }
        NavigationDivider()
        NavigationItem(
            label = AppTab.History.label,
            selected = selected == AppTab.History,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.History, contentDescription = null)
        }
        NavigationDivider()
        NavigationItem(
            label = AppTab.Favorites.label,
            selected = selected == AppTab.Favorites,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = null)
        }
    }
}

@Composable
private fun NavigationDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight(0.58f)
            .background(Parchment.copy(alpha = 0.24f)),
    )
}

@Composable
private fun NavigationItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Tab) { }
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Parchment.copy(
                alpha = if (selected) 1f else 0.58f,
            ),
        ) {
            Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Parchment.copy(alpha = if (selected) 1f else 0.68f),
            fontSize = if (selected) 13.sp else 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.2.sp,
        )
    }
}
