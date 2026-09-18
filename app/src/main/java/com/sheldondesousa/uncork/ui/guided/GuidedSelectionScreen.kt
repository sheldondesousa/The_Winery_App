package com.sheldondesousa.uncork.ui.guided

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.components.BackArrowIcon
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

private enum class LocationPicker { Location, Country, Province }

@Composable
fun GuidedSelectionScreen(
    state: GuidedSelectionState,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onTabSelected: (AppTab) -> Unit,
    onModelSetup: () -> Unit,
    onBack: () -> Unit = {},
) {
    if (state.showResults) {
        GuidedResultsScreen(
            state = state,
            onSuggestionClick = onSuggestionClick,
            onModelSetup = onModelSetup,
            onBack = state::backToForm,
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
    var picker by remember { mutableStateOf<LocationPicker?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(title = "Find", icon = BackArrowIcon, onIconClick = onBack, iconContentDescription = "Back")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Choose any preferences to find a wine.", color = InkSubtle)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${selection.filterCount} filters applied", fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(InkMuted.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.selection = GuidedCriteria() }, enabled = selection.filterCount > 0) {
                    Text("Clear all", color = Wine, fontWeight = FontWeight.Bold)
                }
            }
            Choices("Type", GuidedOptions.types, selection.wineType, hint = "select any that apply") {
                state.selection = state.selection.copy(wineType = state.selection.wineType.toggled(it))
            }
            HorizontalDivider()
            LocationField("Location", selection.locationLabel,
                onClick = { picker = LocationPicker.Location })
            HorizontalDivider()
            Column {
                CategoryTitle("Taste profile")
                Text("Optional — leave blank if you're not sure", color = InkSubtle,
                    style = MaterialTheme.typography.bodySmall)
            }
            Choices("Sweetness", GuidedOptions.sweetness, selection.sweetness) {
                state.selection = state.selection.copy(sweetness = state.selection.sweetness.toggled(it))
            }
            Choices("Tannin", GuidedOptions.tannin, selection.tannin) {
                state.selection = state.selection.copy(tannin = state.selection.tannin.toggled(it))
            }
            Choices("Body", GuidedOptions.body, selection.body, labelFor = { it.removeSuffix("-Bodied") }) {
                state.selection = state.selection.copy(body = state.selection.body.toggled(it))
            }
            Choices("Acidity", GuidedOptions.acidity, selection.acidity) {
                state.selection = state.selection.copy(acidity = state.selection.acidity.toggled(it))
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Hairline)
                .navigationBarsPadding()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .requiredSize(104.dp)
                    .offset(y = (-24).dp)
                    .clip(CircleShape)
                    .background(Parchment),
            )
            SubmitButton(enabled = state.canSearch, onClick = state::search)
        }
    }
    picker?.let { active ->
        val isCountry = active == LocationPicker.Country
        val options = if (isCountry) state.countries else state.provinces
        val selected = if (isCountry) selection.country else selection.province
        ModalBottomSheet(onDismissRequest = { picker = null }, sheetState = sheetState,
            containerColor = Parchment) {
            Text(if (active == LocationPicker.Location) "Location" else "Choose ${if (isCountry) "country" else "province"}",
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (active == LocationPicker.Location) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LocationField("Country", selection.country) { picker = LocationPicker.Country }
                    LocationField("Province", selection.province) { picker = LocationPicker.Province }
                    Button(onClick = { picker = null }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("location-options")) {
                item {
                    TextButton(onClick = {
                        state.selection = if (isCountry) state.selection.withCountry("")
                            else state.selection.copy(province = "")
                        picker = LocationPicker.Location
                    }, modifier = Modifier.fillMaxWidth()) { Text("Any ${if (isCountry) "country" else "province"}") }
                }
                items(options, key = { it }) { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton, onClick = {
                            state.selection = if (isCountry) state.selection.withCountry(option)
                                else state.selection.copy(province = option)
                            picker = LocationPicker.Location
                        }).padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(option, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GuidedResultsScreen(
    state: GuidedSelectionState,
    onSuggestionClick: (WineSuggestion) -> Unit,
    onModelSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val submitted = state.submitted
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(title = "Results", icon = BackArrowIcon, onIconClick = onBack, iconContentDescription = "Back")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            submitted?.let { criteria ->
                val tags = criteria.tags()
                if (tags.isNotEmpty()) {
                    Row {
                        Text("Tags:", fontWeight = FontWeight.Bold, color = Ink,
                            modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { CriteriaTag(it) }
                        }
                    }
                }
            }
            if (submitted != null && submitted != state.selection) {
                Text("Selections changed. Go back and search again to update results.", color = Wine)
            }
            ResultSection("AI Sommelier", state.gemma, onSuggestionClick, isModelSection = true,
                onRetry = state::retryGemma, onModelSetup = onModelSetup)
            ResultSection("Database", state.database, onSuggestionClick)
        }
    }
}

private fun GuidedCriteria.tags(): List<String> = buildList {
    if (wineType.isNotEmpty()) add(wineType.sorted().joinToString(" / "))
    if (country.isNotBlank() || province.isNotBlank()) add(locationLabel)
    if (sweetness.isNotEmpty()) add("Sweetness: ${sweetness.sorted().joinToString(" / ")}")
    if (tannin.isNotEmpty()) add("Tannin: ${tannin.sorted().joinToString(" / ")}")
    if (body.isNotEmpty()) add("Body: ${body.sorted().joinToString(" / ")}")
    if (acidity.isNotEmpty()) add("Acidity: ${acidity.sorted().joinToString(" / ")}")
}

@Composable
private fun CriteriaTag(text: String) {
    Text(
        text,
        color = Wine,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(Wine.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Three dots pulsing in sequence, in place of a Material progress bar's default track color. */
@Composable
private fun ThreeDotsLoadingIndicator(modifier: Modifier = Modifier, dotColor: Color = Wine) {
    val transition = rememberInfiniteTransition(label = "loading-dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Modifier.size(10.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

@Composable
private fun SubmitButton(enabled: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        // A shadow-only layer, offset further up (negative Y) than the button itself, so the
        // shadow reads as cast upward rather than Compose's default downward elevation shadow.
        // Skipped when disabled: full-strength elevation shadow behind a translucent button looked
        // like a dark halo.
        if (enabled) {
            Box(
                Modifier
                    .requiredSize(88.dp)
                    .offset(y = ButtonOffsetY + ShadowOffsetY)
                    .shadow(elevation = 6.dp, shape = CircleShape, clip = false),
            )
        }
        Box(
            modifier = Modifier
                .requiredSize(88.dp)
                .offset(y = ButtonOffsetY)
                .clip(CircleShape)
                .background(if (enabled) Wine else Wine.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Submit"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Submit",
                color = Parchment,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val ButtonOffsetY = (-24).dp
private val ShadowOffsetY = (-6).dp

@Composable
private fun CategoryTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = Ink, modifier = Modifier.semantics { heading() })
}

@Composable
private fun LocationField(title: String, value: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryTitle(title)
        // A read-only input-shaped control: the entire field opens the picker, with no keyboard.
        OutlinedCard(onClick = onClick,
            modifier = Modifier.fillMaxWidth().testTag("${title.lowercase()}-picker")
                .semantics { contentDescription = "$title: ${value.ifBlank { "Any" }}" },
            colors = CardDefaults.outlinedCardColors(containerColor = Parchment)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value.ifBlank { "Select ${title.lowercase()}" },
                    color = if (value.isBlank()) InkSubtle else Ink, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
            }
        }
    }
}

/** Each weighted cell owns the only click target, including its whitespace. */
@Composable
internal fun Choices(
    title: String,
    options: List<String>,
    selected: Set<String>,
    hint: String? = null,
    labelFor: (String) -> String = { it },
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTitle(title)
            hint?.let { Text(" · $it", color = InkSubtle, style = MaterialTheme.typography.bodySmall) }
        }
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { value ->
                    val checked = value in selected
                    Row(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                            .testTag("${title.lowercase()}-$value")
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(value) })
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Circular check matches the reference; checkbox semantics convey multi-select.
                        Canvas(Modifier.size(20.dp)) {
                            drawCircle(if (checked) Wine else InkMuted.copy(alpha = 0.45f),
                                style = if (checked) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5.dp.toPx()))
                            if (checked) {
                                val tick = Path().apply {
                                    moveTo(size.width * 0.25f, size.height * 0.52f)
                                    lineTo(size.width * 0.43f, size.height * 0.70f)
                                    lineTo(size.width * 0.77f, size.height * 0.30f)
                                }
                                drawPath(tick, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                        Text(labelFor(value), color = Ink, modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    result: GuidedResult,
    onSuggestionClick: (WineSuggestion) -> Unit,
    isModelSection: Boolean = false,
    onRetry: () -> Unit = {},
    onModelSetup: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleLarge, color = Wine,
            modifier = Modifier.semantics { heading() })
        when (result) {
            GuidedResult.Idle -> Unit
            GuidedResult.Loading -> {
                ThreeDotsLoadingIndicator()
                Text("Searching $title…", color = InkSubtle)
            }
            GuidedResult.Error -> {
                Text("$title could not complete this search. Retry or open model setup if the model is unavailable.", color = Ink)
                Row {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onModelSetup) { Text("Model setup") }
                }
            }
            is GuidedResult.Complete -> {
                if (result.usedProvinceFallback) {
                    Text("Some results do not have an exact match.", color = InkSubtle)
                }
                if (result.cards.isEmpty()) Text("No matches for these selections.", color = InkSubtle)
                result.cards.forEach { card ->
                    OutlinedCard(onClick = { onSuggestionClick(card) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(card.name, style = MaterialTheme.typography.titleMedium)
                            Text("${card.country} · ${card.province} · ${card.variety}")
                            Text(if (isModelSection) "Model-generated recommendation" else "Critic review", style = MaterialTheme.typography.labelMedium)
                            card.rating?.let { Text("Critic score: $it") }
                            Text("View profile", color = Wine)
                        }
                    }
                }
            }
        }
    }
}
