package com.sheldondesousa.uncork.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.sheldondesousa.uncork.ui.components.AppBottomBar
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

/** The bottom sheet opened by Menu on the main page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(onDismiss: () -> Unit, onSelect: (MenuItem) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Parchment,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                text = "Menu",
                color = Wine,
                fontSize = 20.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() },
            )
            MenuItem.entries.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = Hairline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("menu-${item.name}")
                        .clickable(role = Role.Button) { onSelect(item) }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(item.label, color = Ink, fontSize = 17.sp)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = InkSubtle, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A page for a menu item whose content has not been written yet. */
@Composable
fun MenuInfoScreen(title: String, message: String, onBack: () -> Unit, onHome: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        AppHeader(title = title, icon = Icons.Outlined.Info)
        Column(Modifier.weight(1f).padding(24.dp)) {
            Text(message, color = InkSubtle, fontSize = 16.sp)
        }
        AppBottomBar(onBack = onBack, onHome = onHome)
    }
}
