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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.cardValueOrUnknown
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Wine

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
                Text(wine.name, style = MaterialTheme.typography.titleMedium)
                Text(wine.variety.cardValueOrUnknown())
                Text(
                    text = "${wine.country.cardValueOrUnknown()}, ${wine.province.cardValueOrUnknown()}",
                    color = Wine,
                    fontSize = 12.sp,
                    letterSpacing = 0.3.sp,
                )
                wine.rating?.let { Text("Critic score: $it") }
            }
            Text(
                text = "›",
                color = InkMuted,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}
