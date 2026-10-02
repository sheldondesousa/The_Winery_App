package com.sheldondesousa.uncork.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.ui.conversation.cardValueOrUnknown
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.ResultCardBackground
import com.sheldondesousa.uncork.ui.theme.Wine

/** The shared result-card layout used by both Find and Chat. */
@Composable
fun WineResultCard(
    wine: WineSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = ResultCardBackground,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (wine.source == WineSuggestionSource.GEMMA && wine.requestContext?.trimStart()?.startsWith("{") == true) {
                    CardField(
                        "Variety",
                        wine.variety,
                        MaterialTheme.typography.titleMedium,
                        isHeading = true,
                    )
                    CardField("Type", wine.wineType.displayStyleType())
                    CardField("Country, Province/Region", listOf(wine.country, wine.province)
                        .filter { it.cardValueOrUnknown() != "Unknown" }
                        .joinToString(", ").ifBlank { "Unknown" })
                } else {
                    CardField(
                        "Wine Name",
                        wine.name,
                        MaterialTheme.typography.titleMedium,
                        isHeading = true,
                    )
                    CardField("Variety", wine.variety)
                    CardField("Type", wine.wineType.displayStyleType())
                    CardField("Country, Province/Region", listOf(wine.country, wine.province)
                        .filter { it.cardValueOrUnknown() != "Unknown" }
                        .joinToString(", ").ifBlank { "Unknown" })
                }
                wine.rating?.let { Text("Critic score: $it") }
            }
            Text(
                text = "›",
                color = InkSubtle,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

internal fun String.displayStyleType(): String = when (lowercase()) {
    "rose", "rosé" -> "Rosé"
    "sweet", "dessert" -> "Dessert"
    else -> replaceFirstChar(Char::uppercase)
}

@Composable
private fun CardField(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    isHeading: Boolean = false,
) {
    Column {
        Text(
            text = label.uppercase(),
            modifier = if (isHeading) Modifier.semantics { heading() } else Modifier,
            color = if (isHeading) Wine else InkSubtle,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.8.sp,
        )
        Text(value.cardValueOrUnknown(), style = valueStyle, color = Ink)
    }
}
