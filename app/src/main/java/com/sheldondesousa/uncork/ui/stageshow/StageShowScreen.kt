package com.sheldondesousa.uncork.ui.stageshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.sheldondesousa.uncork.ui.theme.Hairline
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
    val rating: String = "Unknown",
    val cheesePairing: String? = null,
    val verified: Boolean = false,
    val confidencePercent: Int? = null,
)

data class StageWine(
    val ai: WineProfile,
    val kaggle: WineProfile? = null,
    val sourcesAgree: Boolean = false,
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
        rating = sourceRating,
        confidencePercent = confidencePercent,
    ),
)

private enum class WineSource { AI, Kaggle }

@Composable
fun StageShowRoute(
    wine: StageWine,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var source by remember { mutableStateOf(WineSource.AI) }
    var isFavorite by remember { mutableStateOf(false) }
    var rating by remember { mutableIntStateOf(0) }
    var pairing by remember(wine) { mutableStateOf(wine.ai.cheesePairing) }
    val profile = if (source == WineSource.Kaggle) wine.kaggle ?: wine.ai else wine.ai

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to chat" }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Ink,
            )
            Text(
                text = "Back",
                modifier = Modifier.padding(start = 8.dp),
                color = Ink,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(42.dp))
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

        if (source == WineSource.AI && profile.confidencePercent != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "AI CONFIDENCE · ${profile.confidencePercent}%",
                color = Wine,
                fontSize = 10.sp,
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
        DetailRow("VARIETY", profile.variety)
        DetailRow("BODY", profile.body)
        DetailRow("TANNIN", profile.tannin)
        DetailRow("ACIDITY", profile.acidity)
        DetailRow("RATING", profile.rating)
        DetailRow("FLAVOR NOTES", profile.flavorNotes)

        if (pairing != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "CHEESE PAIRING",
                color = Wine,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp,
            )
            Text(
                text = pairing.orEmpty(),
                modifier = Modifier.padding(top = 8.dp),
                color = InkMuted,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
        }

        Spacer(Modifier.height(34.dp))
        StageAction(
            label = if (pairing == null) "Suggest a pairing" else "Change pairing",
            showsFavoriteIcon = false,
            onClick = { pairing = "A creamy Brie would soften the acidity while preserving the wine’s bright fruit." },
        )
        Spacer(Modifier.height(12.dp))
        StageAction(
            label = if (isFavorite) "Saved to favorites" else "Add to favorites",
            selected = isFavorite,
            onClick = { isFavorite = true },
        )

        if (isFavorite) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = if (rating == 0) "YOUR RATING · not yet rated" else "YOUR RATING · $rating / 10",
                color = if (rating == 0) InkMuted else Ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
            )
            RatingDots(rating = rating, onRatingChange = { rating = it })
        }

        Spacer(Modifier.height(24.dp))
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
private fun DetailRow(label: String, value: String) {
    val unknown = value.equals("Unknown", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
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
private fun StageAction(
    label: String,
    selected: Boolean = false,
    showsFavoriteIcon: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Wine else Hairline)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Parchment else Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (showsFavoriteIcon) {
            Icon(
                imageVector = if (selected) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) Parchment else Wine,
            )
        } else {
            Text(text = "›", color = Wine, fontSize = 24.sp)
        }
    }
}

@Composable
private fun RatingDots(rating: Int, onRatingChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        repeat(10) { index ->
            val value = index + 1
            Spacer(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (value <= rating) Wine else Hairline)
                    .clickable(role = Role.RadioButton) { onRatingChange(value) }
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = "Rate $value out of 10"
                    },
            )
        }
    }
}
