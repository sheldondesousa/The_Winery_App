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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

data class WineProfile(
    val winery: String,
    val variety: String,
    val region: String,
    val body: String = "Unknown",
    val tannin: String = "Unknown",
    val acidity: String = "Unknown",
    val flavorNotes: String = "Unknown",
    val suggestedPairing: String = "Unknown",
    val rating: String = "Unknown",
    val cheesePairing: String? = null,
    val verified: Boolean = false,
    val confidencePercent: Int? = null,
)

data class StageWine(
    val ai: WineProfile,
    val kaggle: WineProfile? = null,
    val sourcesAgree: Boolean = false,
    val userRating: Int? = null,
)

fun WineSuggestion.toStageWine(): StageWine = StageWine(
    ai = WineProfile(
        winery = winery,
        variety = variety,
        region = region,
        body = body,
        tannin = tannin,
        acidity = acidity,
        flavorNotes = flavorNotes,
        suggestedPairing = suggestedPairing,
        rating = sourceRating,
        confidencePercent = confidencePercent,
    ),
    userRating = favoriteRating,
)

private enum class WineSource { AI, Kaggle }

private val ShortBackArrow: ImageVector = ImageVector.Builder(
    name = "ShortBackArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = true,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(13f, 6f)
        lineTo(7f, 12f)
        lineTo(13f, 18f)
        moveTo(7f, 12f)
        lineTo(17f, 12f)
    }
}.build()

@Composable
fun StageShowRoute(
    wine: StageWine,
    onBack: () -> Unit,
    initiallyFavorite: Boolean = false,
    onFavoriteChange: (WineSuggestion, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var source by remember { mutableStateOf(WineSource.AI) }
    var isFavorite by remember(wine, initiallyFavorite) { mutableStateOf(initiallyFavorite) }
    val profile = if (source == WineSource.Kaggle) wine.kaggle ?: wine.ai else wine.ai

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        AppHeader(
            title = "Wine Profile",
            icon = ShortBackArrow,
            onIconClick = onBack,
            iconContentDescription = "Back",
            contentPadding = PaddingValues(vertical = 16.dp),
            dividerInset = 0.dp,
        )

        Spacer(Modifier.height(36.dp))
        Text(
            text = profile.winery,
            color = Ink,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Medium,
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
            text = profile.region,
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

        if (source == WineSource.AI) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "AI CONFIDENCE · ${profile.confidencePercent?.let { "$it%" } ?: "UNKNOWN"}",
                color = Wine,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = "Model estimate, not verified accuracy",
                modifier = Modifier.padding(top = 4.dp),
                color = InkMuted,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
            )
        }

        Spacer(Modifier.height(36.dp))
        ShortDetailsGrid(
            details = listOf(
                "VARIETY" to profile.variety,
                "BODY" to profile.body,
                "TANNIN" to profile.tannin,
                "ACIDITY" to profile.acidity,
                "RATING" to profile.rating,
            ),
        )
        LongDetail("FLAVOR NOTES", profile.flavorNotes)

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
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            SaveButton(
                selected = isFavorite,
                onClick = {
                    isFavorite = !isFavorite
                    onFavoriteChange(wine.toWineSuggestion(), isFavorite)
                },
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

fun StageWine.toWineSuggestion(): WineSuggestion = WineSuggestion(
    name = ai.winery,
    region = ai.region,
    winery = ai.winery,
    variety = ai.variety,
    body = ai.body,
    tannin = ai.tannin,
    acidity = ai.acidity,
    flavorNotes = ai.flavorNotes,
    suggestedPairing = ai.suggestedPairing,
    sourceRating = ai.rating,
    confidencePercent = ai.confidencePercent,
    favoriteRating = userRating,
    isFavorite = true,
)

@Composable
private fun SaveButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(88.dp)
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
