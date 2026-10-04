package com.sheldondesousa.uncork.ui.production

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.data.knowledge.ProductionGrape
import com.sheldondesousa.uncork.data.knowledge.ResolvedFact
import com.sheldondesousa.uncork.data.knowledge.WineProduction
import com.sheldondesousa.uncork.ui.components.AppBottomBar
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.ResultCardBackground
import com.sheldondesousa.uncork.ui.theme.Wine

/** The four heading levels, each smaller than the one above: country and place in black, grape and production step in burgundy. */
internal enum class FactHeading(val style: TextStyle) {
    H1(TextStyle(color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)),
    H2(TextStyle(color = Wine, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)),
    H3(TextStyle(color = Ink, fontSize = 18.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)),
    H4(TextStyle(color = Wine, fontSize = 15.sp, fontWeight = FontWeight.Bold)),
}

@Composable
private fun Heading(text: String, level: FactHeading, modifier: Modifier = Modifier) {
    Text(text, style = level.style, modifier = modifier.semantics { heading() })
}

/**
 * Wine Production, one page per layer: Country, then Variety (grape), then Region (place), then how the wine is made
 * there. A breadcrumb line above each list shows the path so far (as in the Directory). Back goes up one layer and
 * leaves the section from the country page; Home returns to the main page.
 */
@Composable
fun WineProductionFactsRoute(
    loadProduction: suspend () -> WineProduction,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var production by remember { mutableStateOf<WineProduction?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(loadProduction) {
        production = runCatching { loadProduction() }.getOrNull()
        failed = production == null
    }
    var countryName by rememberSaveable { mutableStateOf<String?>(null) }
    var grapeName by rememberSaveable { mutableStateOf<String?>(null) }
    var placeName by rememberSaveable { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        when {
            placeName != null -> placeName = null
            grapeName != null -> grapeName = null
            countryName != null -> countryName = null
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)

    Column(modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(title = "Wine Production", icon = Icons.Outlined.Factory)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val data = production
            when {
                data != null -> FactsPage(
                    data, countryName, grapeName, placeName,
                    onCountry = { countryName = it },
                    onGrape = { grapeName = it },
                    onPlace = { placeName = it },
                )
                failed -> Text("The wine production facts could not be loaded.", color = InkSubtle, modifier = Modifier.padding(24.dp))
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Wine, strokeWidth = 2.dp)
            }
        }
        AppBottomBar(onBack = goBack, onHome = onHome)
    }
}

@Composable
private fun FactsPage(
    production: WineProduction,
    countryName: String?,
    grapeName: String?,
    placeName: String?,
    onCountry: (String) -> Unit,
    onGrape: (String) -> Unit,
    onPlace: (String) -> Unit,
) {
    val country = countryName?.takeIf { it == production.country }
    val grape = grapeName?.let { production.grape(it) }
    LazyColumn(Modifier.fillMaxSize().testTag("production-facts"), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            val crumb = when {
                country == null -> "Select Country"
                grape == null -> "$country > Select Variety"
                placeName == null -> "$country > ${grape.name} > Select Region - Wine Area"
                else -> "$country > ${grape.name} > $placeName"
            }
            Breadcrumb(crumb)
        }
        when {
            country == null -> {
                // Only France has production facts so far, so only France has a chevron and opens.
                items(SAMPLE_COUNTRIES, key = { it }) { name ->
                    if (name == production.country) NavRow(name, tag = "production-country-$name") { onCountry(name) }
                    else PlainRow(name, tag = "production-country-$name")
                }
            }
            grape == null -> {
                // Grapes with facts first; the rest follow, marked as coming soon.
                items(grapesInListOrder(production.grapes), key = { it.name }) { g ->
                    if (g.hasFacts) NavRow(g.name, tag = "production-grape-${g.name}") { onGrape(g.name) }
                    else PlainRow(g.name, tag = "production-grape-${g.name}", note = "Coming soon")
                }
            }
            placeName == null -> {
                items(grape.root.flattened(), key = { it.first.name }) { (place, depth) ->
                    NavRow(place.name, tag = "production-place-${place.name}", indent = depth) { onPlace(place.name) }
                }
            }
            else -> placeFacts(production, grape, placeName)
        }
    }
}

/** Where the user is, in the same plain bold line the Directory uses, on the page above the list. */
@Composable
private fun Breadcrumb(text: String) {
    Text(
        text,
        color = Ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("production-breadcrumb"),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.placeFacts(production: WineProduction, grape: ProductionGrape, place: String) {
    val resolved = production.resolve(grape, place)
    if (resolved.facts.isEmpty()) {
        item {
            Text(
                "No production facts have been recorded for ${grape.name} yet.",
                color = InkSubtle,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("production-empty"),
            )
        }
        return
    }
    items(resolved.facts, key = { it.key }) { fact -> FactCard(fact, place) }
    item {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
            if (resolved.sources.isNotEmpty()) {
                Heading("Sources", FactHeading.H4)
                Text(resolved.sources.joinToString(", "), color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            resolved.confidence?.let {
                Text("Confidence ${(it * 100).toInt()}%", color = InkSubtle, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun FactCard(fact: ResolvedFact, place: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .background(ResultCardBackground, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(16.dp)
            .testTag("production-fact-${fact.key}"),
    ) {
        Heading(WineProduction.stepLabel(fact.key), FactHeading.H4)
        Text(fact.value, color = Ink, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
        val origin = if (fact.inherited) "Inherited from ${fact.from}" else "Specific to $place"
        Text(
            "${WineProduction.statusLabel(fact.status)} · $origin",
            color = InkSubtle,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Country, Variety and Region rows share one look (the Directory's: black, 17 sp) and one height, so they line up whatever their text size or note. */
private val ROW_HEIGHT = 72.dp

/** The countries shown on the first page, the one with facts first. Only that one opens. */
private val SAMPLE_COUNTRIES = listOf("France", "Spain", "United States")

/** A row that cannot be opened: no chevron and not tappable, with an optional note under it. */
@Composable
private fun PlainRow(label: String, tag: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().height(ROW_HEIGHT).testTag(tag).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text(label, color = Ink, fontSize = 17.sp)
        if (note != null) Text(note, color = InkSubtle, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
    HorizontalDivider(color = Hairline, modifier = Modifier.padding(horizontal = 24.dp))
}

/** Grapes that have production facts first, then the others, each group alphabetical. */
internal fun grapesInListOrder(grapes: List<ProductionGrape>): List<ProductionGrape> =
    grapes.sortedWith(compareByDescending<ProductionGrape> { it.hasFacts }.thenBy { it.name })

@Composable
private fun NavRow(label: String, tag: String, indent: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .height(ROW_HEIGHT)
            .padding(start = 24.dp + 20.dp * indent, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = InkSubtle, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = Hairline, modifier = Modifier.padding(horizontal = 24.dp))
}
