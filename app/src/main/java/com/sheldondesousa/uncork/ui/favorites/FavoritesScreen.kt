package com.sheldondesousa.uncork.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.BottomNavigation
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

@Composable
fun FavoritesRoute(
    favorites: List<WineSuggestion>,
    onFavoriteClick: (WineSuggestion) -> Unit,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = "Favorites",
            icon = Icons.Outlined.FavoriteBorder,
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(favorites, key = { "${it.winery}|${it.variety}|${it.region}" }) { wine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFavoriteClick(wine) }
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = wine.name,
                            color = Ink,
                            fontSize = 21.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = wine.region,
                            modifier = Modifier.padding(top = 3.dp),
                            color = InkMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(text = "♥", color = Wine, fontSize = 20.sp)
                }
            }
        }
        BottomNavigation(
            selected = AppTab.Favorites,
            onTabSelected = onTabSelected,
        )
    }
}
