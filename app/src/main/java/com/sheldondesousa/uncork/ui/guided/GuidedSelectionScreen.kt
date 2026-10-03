package com.sheldondesousa.uncork.ui.guided

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.components.AppBottomBar
import com.sheldondesousa.uncork.ui.components.ElevatedBottomAction
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.SourceQueryStatus
import com.sheldondesousa.uncork.ui.conversation.SourceResult
import com.sheldondesousa.uncork.ui.conversation.SourceResultCard
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.data.reviews.ScoreBand
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

/** One tile per field on the Find form; tapping a tile opens its options in a bottom sheet. */
private enum class FormField(val label: String) {
    Type("Type"), Variety("Variety"), Sweetness("Sweetness"), Tannin("Tannin"), Body("Body"), Acidity("Acidity"),
    Country("Country"), Province("Province"),
}

private fun FormField.options(): List<String> = when (this) {
    FormField.Type -> GuidedOptions.types
    FormField.Variety -> GuidedOptions.varieties
    FormField.Sweetness -> GuidedOptions.sweetness
    FormField.Tannin -> GuidedOptions.tannin
    FormField.Body -> GuidedOptions.body
    FormField.Acidity -> GuidedOptions.acidity
    FormField.Country, FormField.Province -> emptyList()
}

/** Every field on this form is single-select: choosing a value (or "Any") replaces whatever was there before. */
private fun FormField.selectedValue(selection: GuidedCriteria): String = when (this) {
    FormField.Type -> selection.wineType
    FormField.Variety -> selection.variety
    FormField.Sweetness -> selection.sweetness
    FormField.Tannin -> selection.tannin
    FormField.Body -> selection.body
    FormField.Acidity -> selection.acidity
    FormField.Country -> selection.country
    FormField.Province -> selection.province
}

private fun FormField.select(selection: GuidedCriteria, value: String): GuidedCriteria = when (this) {
    FormField.Type -> selection.copy(wineType = value)
    FormField.Variety -> selection.copy(variety = value)
    FormField.Sweetness -> selection.copy(sweetness = value)
    FormField.Tannin -> selection.copy(tannin = value)
    FormField.Body -> selection.copy(body = value)
    FormField.Acidity -> selection.copy(acidity = value)
    FormField.Country -> selection.withCountry(value)
    FormField.Province -> selection.copy(province = value)
}

private fun FormField.summary(selection: GuidedCriteria): String {
    val value = selectedValue(selection)
    if (value.isBlank()) return "Any"
    return if (this == FormField.Body) value.removeSuffix("-Bodied") else value
}

@Composable
fun GuidedSelectionScreen(
    state: GuidedSelectionState,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onTabSelected: (AppTab) -> Unit,
    onBack: () -> Unit = {},
    onHome: () -> Unit = onBack,
) {
    if (state.showResults) {
        GuidedResultsScreen(
            state = state,
            onSuggestionClick = onSuggestionClick,
            onBack = state::backToForm,
            onHome = onHome,
        )
    } else {
        GuidedSelectionFormScreen(
            state = state,
            onTabSelected = onTabSelected,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedSelectionFormScreen(
    state: GuidedSelectionState,
    onTabSelected: (AppTab) -> Unit,
    onBack: () -> Unit,
) {
    val selection = state.selection
    var activeField by remember { mutableStateOf<FormField?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(title = "Find", icon = Icons.Outlined.Search, iconContentDescription = "Find")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Enter a Spec",
                    color = Wine,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() },
                )
                Text("Choose your preference", color = InkSubtle)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${selection.filterCount} filters applied",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.selection = GuidedCriteria() }, enabled = selection.filterCount > 0) {
                    Text(
                        "Clear all",
                        color = Wine,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            CategoryTitle("Wine Style & Origin")
            FormTile(FormField.Type, selection, Modifier.fillMaxWidth()) { activeField = it }
            FormTile(FormField.Variety, selection, Modifier.fillMaxWidth()) { activeField = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormTile(FormField.Country, selection, Modifier.weight(1f)) { activeField = it }
                FormTile(FormField.Province, selection, Modifier.weight(1f)) { activeField = it }
            }
            HorizontalDivider()
            CategoryTitle("Taste Profile")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormTile(FormField.Sweetness, selection, Modifier.weight(1f)) { activeField = it }
                FormTile(FormField.Tannin, selection, Modifier.weight(1f)) { activeField = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormTile(FormField.Body, selection, Modifier.weight(1f)) { activeField = it }
                FormTile(FormField.Acidity, selection, Modifier.weight(1f)) { activeField = it }
            }
            HorizontalDivider()
            TextButton(onClick = { onTabSelected(AppTab.Conversation) }, contentPadding = PaddingValues(0.dp)) {
                Text("Not sure what these mean? Chat with the sommelier instead.", color = InkSubtle)
            }
            Text(
                "Database preferences match descriptions in critic reviews; some wines may have no recorded match. " +
                    "Type filters use mapped varieties; unclassified wines may be missed.",
                style = MaterialTheme.typography.bodySmall, color = InkSubtle,
            )
            // Clears the Submit button, which floats above the bottom bar and would otherwise cover this text.
            Spacer(Modifier.height(48.dp))
        }
        AppBottomBar(onBack = onBack) {
            ElevatedBottomAction(
                label = "Submit",
                enabled = state.canSearch,
                onClick = state::search,
            )
        }
    }
    activeField?.let { field ->
        FieldBottomSheet(field = field, state = state, sheetState = sheetState, onDismiss = { activeField = null })
    }
}

/** Bottom sheet fixed at half the screen height; its option list scrolls independently when it overflows that space. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldBottomSheet(
    field: FormField,
    state: GuidedSelectionState,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val options = when (field) {
        FormField.Country -> state.countries
        FormField.Province -> state.provinces
        else -> field.options()
    }
    val selectedValue = field.selectedValue(state.selection)
    val labelFor: (String) -> String =
        if (field == FormField.Body) { { it.removeSuffix("-Bodied") } } else { { it } }

    fun select(value: String) {
        state.selection = field.select(state.selection, value)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Parchment) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
            Text("Choose ${field.label.lowercase()}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { heading() })
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("${field.label.lowercase()}-options")) {
                item {
                    SingleSelectRow(
                        label = "Any ${field.label.lowercase()}",
                        selected = selectedValue.isEmpty(),
                        onClick = { select("") },
                    )
                    HorizontalDivider(color = Hairline)
                }
                itemsIndexed(options, key = { _, option -> option }) { index, option ->
                    if (index > 0) HorizontalDivider(color = Hairline)
                    SingleSelectRow(
                        label = labelFor(option),
                        selected = option == selectedValue,
                        onClick = { select(option) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun SingleSelectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("${label.lowercase()}-option")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected = selected)
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GuidedResultsScreen(
    state: GuidedSelectionState,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val submitted = state.submitted
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(
            title = "Results",
            icon = Icons.Outlined.Search,
            iconContentDescription = "Find",
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = 20.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            submitted?.let { criteria ->
                val tags = criteria.tags()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Tags: ") }
                            append(tags.joinToString(" | "))
                        },
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    OutlinedButton(
                        onClick = state::searchWeb,
                        enabled = state.web !is GuidedResult.Loading,
                        modifier = Modifier.testTag("web-search-button"),
                        border = BorderStroke(1.dp, Wine.copy(alpha = if (state.web is GuidedResult.Loading) 0.3f else 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Wine),
                    ) { Text("Web Search") }
                }
            }
            if (submitted != null && submitted != state.selection) {
                Text("Selections changed. Go back and search again to update results.", color = Wine)
            }
            ResultFilters(state)
            ReviewsSection(state, onSuggestionClick)
            GuidedSourceSection(
                source = WineSuggestionSource.CACHE,
                result = state.extended,
                onSuggestionClick = onSuggestionClick,
            )
            if (state.web != GuidedResult.Idle) {
                GuidedSourceSection(
                    source = WineSuggestionSource.WEB_SEARCH,
                    result = state.web,
                    onSuggestionClick = onSuggestionClick,
                    onRetry = state::searchWeb,
                )
            }
        }
        AppBottomBar(onBack = onBack, onHome = onHome)
    }
}

/** Score tabs on the left, the single sort on the right, at the top of the results. */
@Composable
private fun ResultFilters(state: GuidedSelectionState) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScoreBand.entries.forEach { band ->
            val selected = state.scoreTab == band
            Text(
                text = "${band.label} pts",
                color = if (selected) Parchment else Wine,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .testTag("score-tab-${band.label}")
                    .clip(CircleShape)
                    .background(if (selected) Wine else Wine.copy(alpha = 0.10f))
                    .selectable(selected = selected, role = Role.Tab, onClick = { state.selectTab(band) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            OutlinedButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.testTag("sort-button"),
                border = BorderStroke(1.dp, Wine.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Wine),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(if (state.sort == GuidedSort.Ranked) "Sort" else state.sort.label, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                GuidedSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                fontWeight = if (option == state.sort) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            state.selectSort(option)
                            sortMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}

/** The reviews for the open score tab, ten at a time, with a More button when there are more. */
@Composable
private fun ReviewsSection(state: GuidedSelectionState, onSuggestionClick: (WineSuggestion) -> Unit) {
    val band = state.scoreTab
    val tab = state.tab(band)
    Column {
        HorizontalDivider(thickness = 1.5.dp)
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab.usedProvinceFallback) {
                Text("Some results do not have an exact match.", color = InkSubtle)
            }
            val sourceResult = when (tab.status) {
                is GuidedResult.Loading, GuidedResult.Idle ->
                    SourceResult(WineSuggestionSource.KAGGLE, SourceQueryStatus.LOADING)
                else -> SourceResult(WineSuggestionSource.KAGGLE, SourceQueryStatus.COMPLETE, state.visibleReviews(band))
            }
            SourceResultCard(sourceResult = sourceResult, onSuggestionClick = onSuggestionClick)
            if (tab.hasMore || tab.loadingMore) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = { state.loadMore(band) },
                        enabled = !tab.loadingMore,
                        modifier = Modifier.testTag("more-button"),
                        border = BorderStroke(1.dp, Wine.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Wine),
                    ) {
                        if (tab.loadingMore) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Wine, strokeWidth = 1.5.dp)
                        } else {
                            Text("More")
                        }
                    }
                }
            }
        }
    }
}

private fun GuidedCriteria.tags(): List<String> = buildList {
    if (wineType.isNotBlank()) add(wineType)
    if (variety.isNotBlank()) add(variety)
    if (country.isNotBlank() || province.isNotBlank()) add(locationLabel)
    if (sweetness.isNotBlank()) add("Sweetness: $sweetness")
    if (tannin.isNotBlank()) add("Tannin: $tannin")
    if (body.isNotBlank()) add("Body: $body")
    if (acidity.isNotBlank()) add("Acidity: $acidity")
}

@Composable
private fun CategoryTitle(title: String) {
    Text(
        text = title,
        color = Wine,
        fontSize = 18.sp,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.semantics { heading() },
    )
}

/** A tile summarizing one field's current selection; tapping it opens that field's bottom sheet. */
@Composable
private fun FormTile(field: FormField, selection: GuidedCriteria, modifier: Modifier = Modifier, onClick: (FormField) -> Unit) {
    val value = field.summary(selection)
    OutlinedCard(onClick = { onClick(field) },
        modifier = modifier.testTag("${field.label.lowercase()}-tile")
            .semantics { contentDescription = "${field.label}: $value" },
        colors = CardDefaults.outlinedCardColors(containerColor = Parchment)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = field.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
            }
            Text(value, color = if (value == "Any") InkSubtle else Wine, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Same 20dp indicator box as Choices' checkbox circle, drawn with radio (single-select) semantics instead of a tick. */
@Composable
private fun RadioDot(selected: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val color = if (selected) Wine else InkSubtle
        drawCircle(color, style = Stroke(1.5.dp.toPx()))
        if (selected) {
            drawCircle(color, radius = size.minDimension / 2 * 0.5f)
        }
    }
}

@Composable
private fun GuidedSourceSection(
    source: WineSuggestionSource,
    result: GuidedResult,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onRetry: () -> Unit = {},
) {
    Column {
        HorizontalDivider(thickness = 1.5.dp)
        Column(
            modifier = Modifier.padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (result is GuidedResult.Complete && result.usedProvinceFallback) {
                Text("Some results do not have an exact match.", color = InkSubtle)
            }
            val sourceResult = when (result) {
                GuidedResult.Idle -> null
                is GuidedResult.Loading -> SourceResult(source, SourceQueryStatus.LOADING, result.cards)
                GuidedResult.Error -> SourceResult(source, SourceQueryStatus.FAILED)
                is GuidedResult.Complete -> SourceResult(source, SourceQueryStatus.COMPLETE, result.cards)
            }
            sourceResult?.let {
                SourceResultCard(
                    sourceResult = it,
                    onSuggestionClick = onSuggestionClick,
                    onRetry = if (result == GuidedResult.Error) onRetry else null,
                )
            }
        }
    }
}
