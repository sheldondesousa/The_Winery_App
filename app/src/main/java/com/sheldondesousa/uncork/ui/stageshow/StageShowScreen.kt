package com.sheldondesousa.uncork.ui.stageshow

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.components.BackArrowIcon
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

data class WineProfile(
    val winery: String,
    val name: String = winery,
    val country: String = "Unknown",
    val wineType: String = "Unknown",
    val variety: String,
    val province: String,
    val sweetness: String = "Unknown",
    val body: String = "Unknown",
    val tannin: String = "Unknown",
    val acidity: String = "Unknown",
    val flavorNotes: String = "Unknown",
    val suggestedPairing: String = "Unknown",
    val summary: String = "Unknown",
    val rating: Int? = null,
    val reviewSummary: String = "Unknown",
    val webSummary: String = "Unknown",
    val source: WineSuggestionSource = WineSuggestionSource.GEMMA,
    val requestContext: String? = null,
    val profileComplete: Boolean = false,
    val cheesePairing: String? = null,
    val verified: Boolean = false,
)

data class StageWine(
    val ai: WineProfile,
    val kaggle: WineProfile? = null,
    val sourcesAgree: Boolean = false,
    val userRating: Int? = null,
)

fun WineSuggestion.toStageWine(): StageWine = StageWine(
    ai = WineProfile(
        name = name,
        winery = winery,
        country = country,
        wineType = wineType,
        variety = variety,
        province = province,
        sweetness = sweetness,
        body = body,
        tannin = tannin,
        acidity = acidity,
        flavorNotes = flavorNotes,
        suggestedPairing = suggestedPairing,
        summary = summary,
        rating = rating,
        reviewSummary = reviewSummary,
        webSummary = webSummary,
        source = source,
        requestContext = requestContext,
        profileComplete = profileComplete,
    ),
    userRating = favoriteRating,
)

private enum class WineSource { AI, Kaggle }

@Composable
fun StageShowRoute(
    wine: StageWine,
    onBack: () -> Unit,
    loadProfile: suspend (WineSuggestion) -> WineSuggestion = { it },
    initiallyFavorite: Boolean = false,
    onFavoriteChange: (WineSuggestion, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var source by remember { mutableStateOf(WineSource.AI) }
    var displayedWine by remember(wine) { mutableStateOf(wine) }
    var loadingProfile by remember(wine) { mutableStateOf(true) }
    var isFavorite by remember(wine, initiallyFavorite) { mutableStateOf(initiallyFavorite) }
    val profile = if (source == WineSource.Kaggle) {
        displayedWine.kaggle ?: displayedWine.ai
    } else {
        displayedWine.ai
    }

    LaunchedEffect(wine) {
        // Chat's own Gemma response already asks for (and usually gets) a summary on every
        // card it returns — if it's already here, the full profile reload this triggers is a
        // second ~10-30s on-device inference call purely to re-fetch something Chat already
        // had, and "Loading details…" sits on screen the whole time for no reason. Only run it
        // when the summary actually still needs filling in.
        if (wine.ai.summary.isResolvedValue()) {
            loadingProfile = false
        } else {
            val loaded = runCatching { loadProfile(wine.toWineSuggestion()) }
                .getOrDefault(wine.toWineSuggestion())
            displayedWine = wine.copy(ai = loaded.toStageWine().ai)
            loadingProfile = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = "Attributes",
            icon = BackArrowIcon,
            onIconClick = onBack,
            iconContentDescription = "Back",
            modifier = Modifier.padding(horizontal = 22.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            dividerInset = 0.dp,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Text(
            text = profile.name,
            color = Ink,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = profile.winery,
            modifier = Modifier.padding(top = 8.dp),
            color = InkMuted,
            fontSize = 17.sp,
            lineHeight = 23.sp,
        )
        if (source == WineSource.Kaggle && profile.verified) {
            Text(
                text = "VERIFIED WINERY",
                modifier = Modifier.padding(top = 8.dp),
                color = Wine,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
        }
        Text(
            text = listOf(profile.province, profile.country)
                .filterNot { it.equals("Unknown", ignoreCase = true) }
                .joinToString(", ")
                .ifBlank { "Unknown" },
            modifier = Modifier.padding(top = 10.dp),
            color = Wine,
            fontSize = 17.sp,
            lineHeight = 23.sp,
        )

        if (wine.kaggle != null) {
            Spacer(Modifier.height(28.dp))
            SourceSelector(
                selected = source,
                sourcesAgree = wine.sourcesAgree,
                onSelect = { source = it },
            )
        }

        Spacer(Modifier.height(36.dp))
        ShortDetailsGrid(
            details = buildList {
                add("VARIETY" to profile.variety)
                add("COUNTRY" to profile.country)
                add("PROVINCE" to profile.province)
                if (profile.sweetness != "Unknown") add("SWEETNESS" to profile.sweetness)
                add("BODY" to profile.body.removeSuffix("-Bodied"))
                add("TANNIN" to profile.tannin)
                add("ACIDITY" to profile.acidity)
                profile.rating?.let { add("RATING" to it.toString()) }
            },
        )
        LongDetail("FLAVOR NOTES", profile.flavorNotes)

        when (profile.source) {
            WineSuggestionSource.GEMMA -> LongDetail(
                "SUMMARY",
                when {
                    profile.summary.isResolvedValue() -> profile.summary
                    loadingProfile -> "Loading details…"
                    else -> "Unknown"
                },
            )
            WineSuggestionSource.KAGGLE -> LongDetail("CRITIC REVIEW", profile.reviewSummary)
            WineSuggestionSource.CACHE -> LongDetail("SAVED SUMMARY", profile.webSummary)
            WineSuggestionSource.WEB_SEARCH -> LongDetail("WEB SUMMARY", profile.webSummary)
        }

        LongDetail("SUGGESTED PAIRING", profile.suggestedPairing)

        Spacer(Modifier.height(28.dp))
        Text(
            text = wine.userRating?.let { "YOUR RATING · $it / 10" } ?: "YOU HAVE NOT TRIED THIS WINE",
            color = if (wine.userRating == null) InkMuted else Ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
        )

        Spacer(Modifier.height(32.dp))
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
            SaveButton(
                selected = isFavorite,
                onClick = {
                    isFavorite = !isFavorite
                    onFavoriteChange(displayedWine.toWineSuggestion(), isFavorite)
                },
            )
        }
    }
}

private fun String.isResolvedValue(): Boolean =
    isNotBlank() && !equals("Unknown", ignoreCase = true)

fun StageWine.toWineSuggestion(): WineSuggestion = WineSuggestion(
    name = ai.name,
    country = ai.country,
    province = ai.province,
    wineType = ai.wineType,
    winery = ai.winery,
    variety = ai.variety,
    sweetness = ai.sweetness,
    body = ai.body,
    tannin = ai.tannin,
    acidity = ai.acidity,
    flavorNotes = ai.flavorNotes,
    suggestedPairing = ai.suggestedPairing,
    summary = ai.summary,
    rating = ai.rating ?: kaggle?.rating,
    reviewSummary = ai.reviewSummary,
    webSummary = ai.webSummary,
    source = ai.source,
    requestContext = ai.requestContext,
    profileComplete = ai.profileComplete,
    favoriteRating = userRating,
    isFavorite = true,
)

@Composable
private fun SaveButton(selected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        // A shadow-only layer, offset further up (negative Y) than the button itself, so the
        // shadow reads as cast upward rather than Compose's default downward elevation shadow.
        Box(
            Modifier
                .requiredSize(88.dp)
                .offset(y = ButtonOffsetY + ShadowOffsetY)
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false),
        )
        Box(
            modifier = Modifier
                .requiredSize(88.dp)
                .offset(y = ButtonOffsetY)
                .clip(CircleShape)
                .background(if (selected) Wine.copy(alpha = 0.18f) else Wine)
                .clickable(role = Role.Switch, onClick = onClick)
                .semantics {
                    role = Role.Switch
                    contentDescription = "Save favorite, ${if (selected) "on" else "off"}"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (selected) "Saved" else "Save",
                color = if (selected) Wine else Parchment,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val ButtonOffsetY = (-24).dp
private val ShadowOffsetY = (-6).dp

@Composable
private fun SourceSelector(
    selected: WineSource,
    sourcesAgree: Boolean,
    onSelect: (WineSource) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceOption("AI", selected == WineSource.AI) { onSelect(WineSource.AI) }
        Text(" / ", color = InkMuted, fontSize = 13.sp)
        SourceOption("Kaggle", selected == WineSource.Kaggle) { onSelect(WineSource.Kaggle) }
        Text(
            text = if (sourcesAgree) "SIMILAR PICK" else "DIFFERENT TAKE",
            modifier = Modifier.padding(start = 18.dp),
            color = Wine,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.3.sp,
        )
    }
}

@Composable
private fun SourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier.clickable(role = Role.Tab, onClick = onClick),
        color = if (selected) Ink else InkMuted,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
    )
}

@Composable
private fun ShortDetailsGrid(details: List<Pair<String, String>>) {
    details.chunked(2).forEach { rowDetails ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            rowDetails.forEach { (label, value) ->
                DetailCell(
                    label = label,
                    value = value,
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowDetails.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val unknown = value.equals("Unknown", ignoreCase = true)
    Column(
        modifier = modifier.padding(bottom = 24.dp),
    ) {
        Text(
            text = label,
            color = Wine,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 7.dp),
            color = if (unknown) InkMuted else Ink,
            fontSize = 15.sp,
            lineHeight = 28.sp,
            fontStyle = if (unknown) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

@Composable
private fun LongDetail(label: String, value: String) {
    DetailCell(
        label = label,
        value = value,
        modifier = Modifier.fillMaxWidth(),
    )
}
