package com.sheldondesousa.uncork.ui.landing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.conversation.AppTab
import androidx.compose.material3.Text
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

private data class LandingDestination(
    val tab: AppTab,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private val LandingDestinations = listOf(
    LandingDestination(
        tab = AppTab.Find,
        title = "Discover a bottle",
        description = "Filter by taste, occasion, and region to discover a wine.",
        icon = Icons.Outlined.Search,
    ),
    LandingDestination(
        tab = AppTab.Conversation,
        title = "Consult an AI Sommelier",
        description = "Describe what you're in the mood for and get a recommendation.",
        icon = Icons.Outlined.ChatBubbleOutline,
    ),
    LandingDestination(
        tab = AppTab.Favorites,
        title = "My wine list",
        description = "Revisit the bottles you've saved and rated.",
        icon = Icons.Outlined.FavoriteBorder,
    ),
)

// Matches the height the removed bottom navigation bar used to occupy, so the last
// card's base lines up with where its top edge would have been.
private val BottomNavReservedHeight = 72.dp

@Composable
fun LandingRoute(
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text(
            text = "Uncork",
            color = Ink,
            fontSize = 52.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 58.sp,
        )
        Text(
            text = "AI SOMMELIER",
            color = Wine,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(56.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LandingDestinations.forEach { destination ->
                LandingCard(
                    destination = destination,
                    onClick = { onTabSelected(destination.tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(BottomNavReservedHeight))
    }
}

private val LandingCardIconSize = 34.dp

@Composable
private fun LandingCard(
    destination: LandingDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.copy(alpha = 0.04f))
            .border(1.dp, Hairline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = destination.title }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = Wine,
                modifier = Modifier.size(LandingCardIconSize),
            )
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = destination.title,
                    color = Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = destination.description,
                    modifier = Modifier.padding(top = 4.dp),
                    color = InkMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = InkMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}
