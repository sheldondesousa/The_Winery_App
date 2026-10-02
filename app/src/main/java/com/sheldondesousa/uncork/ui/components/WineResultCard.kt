package com.sheldondesousa.uncork.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.ui.conversation.cardValueOrUnknown
import com.sheldondesousa.uncork.ui.theme.InkSubtle

/** The shared result-card layout used by both Find and Chat. */
@Composable
fun WineResultCard(
    wine: WineSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (wine.source == WineSuggestionSource.GEMMA && wine.requestContext?.trimStart()?.startsWith("{") == true) {
                    CardField("Variety", wine.variety, MaterialTheme.typography.titleMedium)
                    CardField("Type", wine.wineType.displayStyleType())
                    CardField("Country, Province/Region", listOf(wine.country, wine.province)
                        .filter { it.cardValueOrUnknown() != "Unknown" }
                        .joinToString(", ").ifBlank { "Unknown" })
                } else {
                    CardField("Wine Name", wine.name, MaterialTheme.typography.titleMedium)
                    CardField("Country", wine.country)
                    CardField("Province/Region", wine.province)
                    CardField("Variety", wine.variety)
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
) {
    Column {
        Text(
            text = label.uppercase(),
            color = InkSubtle,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
        )
        Text(value.cardValueOrUnknown(), style = valueStyle)
    }
}
