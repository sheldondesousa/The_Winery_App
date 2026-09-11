package com.sheldondesousa.uncork.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.BottomNavigation
import com.sheldondesousa.uncork.ui.theme.Parchment

@Composable
fun FavoritesRoute(
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .statusBarsPadding(),
    ) {
        AppHeader(title = "Favorites")
        Spacer(modifier = Modifier.weight(1f))
        BottomNavigation(
            selected = AppTab.Favorites,
            onTabSelected = onTabSelected,
        )
    }
}
