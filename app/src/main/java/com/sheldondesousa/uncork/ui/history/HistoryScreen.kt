package com.sheldondesousa.uncork.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.BottomNavigation
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryRoute(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding(),
    ) {
        HistoryHeader()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (entries.isEmpty()) {
                EmptyHistory(onStartConversation = { onTabSelected(AppTab.Conversation) })
            } else {
                HistoryList(entries = entries, onEntryClick = onEntryClick)
            }
        }

        BottomNavigation(
            selected = AppTab.History,
            onTabSelected = onTabSelected,
        )
    }
}

@Composable
private fun HistoryHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Uncork",
            color = Ink,
            fontSize = 30.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "HISTORY",
            modifier = Modifier.padding(top = 1.dp),
            color = Wine,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun EmptyHistory(onStartConversation: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Your wine suggestions will appear here.",
            color = InkMuted,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
        )
        Text(
            text = "Start a conversation",
            modifier = Modifier
                .padding(top = 18.dp)
                .clickable(role = Role.Button, onClick = onStartConversation)
                .padding(8.dp),
            color = Wine,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
) {
    val groups = remember(entries) { entries.groupByDate() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, top = 12.dp, end = 22.dp, bottom = 24.dp),
    ) {
        groups.forEach { (date, datedEntries) ->
            item(key = "date-$date") { DateHeader(date) }
            items(datedEntries, key = HistoryEntry::id) { entry ->
                HistoryRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate) {
    Text(
        text = date.displayLabel(),
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
        color = Wine,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp,
    )
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Open ${entry.suggestion.name} details"
            }
            .padding(vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.suggestion.name,
                    color = Ink,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = entry.suggestion.region,
                    modifier = Modifier.padding(top = 3.dp),
                    color = InkMuted,
                    fontSize = 12.sp,
                    letterSpacing = 0.2.sp,
                )
            }
            Text(
                text = "›",
                modifier = Modifier.padding(start = 16.dp),
                color = InkMuted,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Text(
            text = entry.excerpt,
            modifier = Modifier.padding(top = 10.dp, end = 24.dp),
            color = Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontFamily = FontFamily.Serif,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.suggestion.isFavorite) {
            Text(
                text = entry.suggestion.favoriteRating?.let { "$it / 10" } ?: "FAVORITED",
                modifier = Modifier.padding(top = 10.dp),
                color = Wine,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(1.dp)
                .background(Hairline),
        )
    }
}

private fun List<HistoryEntry>.groupByDate(): List<Pair<LocalDate, List<HistoryEntry>>> {
    val zone = ZoneId.systemDefault()
    return groupBy { entry ->
        Instant.ofEpochMilli(entry.createdAtEpochMillis).atZone(zone).toLocalDate()
    }.toList().sortedByDescending { it.first }
}

private fun LocalDate.displayLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())).uppercase()
    }
}
