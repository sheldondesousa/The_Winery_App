package com.sheldondesousa.uncork.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

/** The shared 72dp footer used across Find, Results, and Summary. */
@Composable
fun AppBottomBar(
    onBack: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
    centerContent: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hairline)
            .navigationBarsPadding()
            .height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null || onHome != null) {
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (onBack != null) {
                        BottomBarDestination(
                            label = "Back",
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = Wine,
                                )
                            },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (onHome != null) {
                        BottomBarDestination(
                            label = "Home",
                            onClick = onHome,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            icon = {
                                Icon(
                                    Icons.Outlined.Home,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = Wine,
                                )
                            },
                        )
                    }
                }
            }
        }
        centerContent()
    }
}

/** The shared raised action used for Menu, Submit, and Ask. */
@Composable
fun ElevatedBottomAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String = label,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredSize(104.dp)
                .offset(y = (-24).dp)
                .clip(CircleShape)
                .background(Parchment),
        )
        if (enabled) {
            Box(
                Modifier
                    .requiredSize(88.dp)
                    .offset(y = (-30).dp)
                    .shadow(elevation = 6.dp, shape = CircleShape, clip = false),
            )
        }
        Box(
            modifier = Modifier
                .requiredSize(88.dp)
                .offset(y = (-24).dp)
                .clip(CircleShape)
                .background(if (enabled) Wine else Wine.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Parchment,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BottomBarDestination(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
            icon()
        }
        Text(
            text = label,
            color = Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
