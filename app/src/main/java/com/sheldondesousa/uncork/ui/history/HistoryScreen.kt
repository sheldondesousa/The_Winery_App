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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.BottomNavigation
import com.sheldondesousa.uncork.ui.components.AppHeader
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

private enum class ClearMode { Manual, All }

@Composable
fun HistoryRoute(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    onDeleteEntries: (Set<Long>) -> Unit = {},
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearMode by remember { mutableStateOf<ClearMode?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val isSelecting = clearMode != null

    LaunchedEffect(entries) {
        selectedIds = selectedIds.intersect(entries.mapTo(mutableSetOf(), HistoryEntry::id))
        if (entries.isEmpty()) clearMode = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = "History",
            icon = Icons.Outlined.History,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (entries.isNotEmpty()) {
                HistoryList(
                    entries = entries,
                    isSelecting = isSelecting,
                    selectedIds = selectedIds,
                    onEntryClick = onEntryClick,
                    clearMode = clearMode,
                    onClear = {
                        clearMode = if (clearMode == ClearMode.Manual) null else ClearMode.Manual
                        selectedIds = emptySet()
                    },
                    onClearAll = {
                        if (clearMode == ClearMode.All) {
                            clearMode = null
                            selectedIds = emptySet()
                        } else {
                            clearMode = ClearMode.All
                            selectedIds = entries.mapTo(mutableSetOf(), HistoryEntry::id)
                        }
                    },
                    onSelectionChange = { entryId, selected ->
                        selectedIds = if (selected) selectedIds + entryId else selectedIds - entryId
                    },
                )
                if (isSelecting) {
                    val canDelete = selectedIds.isNotEmpty()
                    val deleteColor = if (canDelete) Color.Black else InkMuted.copy(alpha = 0.45f)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clickable(enabled = canDelete, role = Role.Button) {
                                onDeleteEntries(selectedIds)
                                selectedIds = emptySet()
                                clearMode = null
                            }
                            .semantics {
                                role = Role.Button
                                contentDescription = "Delete selected history"
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = deleteColor,
                        )
                        Text(
                            text = "Delete",
                            color = deleteColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        BottomNavigation(
            selected = AppTab.History,
            onTabSelected = onTabSelected,
        )
    }
}

@Composable
private fun HistoryActions(
    clearMode: ClearMode?,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClear,
            enabled = clearMode != ClearMode.All,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = Wine,
                containerColor = Hairline,
                disabledContentColor = InkMuted.copy(alpha = 0.45f),
                disabledContainerColor = Hairline.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                text = if (clearMode == ClearMode.Manual) "Cancel" else "Clear",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(
            onClick = onClearAll,
            enabled = clearMode != ClearMode.Manual,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = Wine,
                containerColor = Hairline,
                disabledContentColor = InkMuted.copy(alpha = 0.45f),
                disabledContainerColor = Hairline.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                text = if (clearMode == ClearMode.All) "Cancel" else "Clear All",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    isSelecting: Boolean,
    clearMode: ClearMode?,
    selectedIds: Set<Long>,
    onEntryClick: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
    onSelectionChange: (Long, Boolean) -> Unit,
) {
    val groups = remember(entries) { entries.groupByDate() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 22.dp,
            top = 12.dp,
            end = 22.dp,
            bottom = if (isSelecting) 88.dp else 24.dp,
        ),
    ) {
        groups.forEachIndexed { index, (date, datedEntries) ->
            item(key = "date-$date") {
                DateHeader(
                    date = date,
                    showActions = index == 0,
                    clearMode = clearMode,
                    onClear = onClear,
                    onClearAll = onClearAll,
                )
            }
            items(datedEntries, key = HistoryEntry::id) { entry ->
                HistoryRow(
                    entry = entry,
                    isSelecting = isSelecting,
                    isSelected = entry.id in selectedIds,
                    onClick = {
                        if (isSelecting) {
                            onSelectionChange(entry.id, entry.id !in selectedIds)
                        } else {
                            onEntryClick(entry)
                        }
                    },
                    onSelectionChange = { onSelectionChange(entry.id, it) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    showActions: Boolean,
    clearMode: ClearMode?,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = date.displayLabel(),
            color = Wine,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        if (showActions) {
            HistoryActions(
                clearMode = clearMode,
                onClear = onClear,
                onClearAll = onClearAll,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (isSelecting) {
                    "${if (isSelected) "Deselect" else "Select"} ${entry.suggestion.name}"
                } else {
                    "Open ${entry.suggestion.name} details"
                }
            }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isSelecting) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Wine,
                    uncheckedColor = InkMuted,
                    checkmarkColor = Parchment,
                ),
            )
        }
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
            Text(
                text = "Your Request: ${entry.requestKeywords.joinToString(" · ")}",
                modifier = Modifier.padding(top = 10.dp, end = 24.dp),
                color = Ink,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
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
        if (!isSelecting) {
            Text(
                text = "›",
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 16.dp),
                color = InkMuted,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
            )
        }
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
