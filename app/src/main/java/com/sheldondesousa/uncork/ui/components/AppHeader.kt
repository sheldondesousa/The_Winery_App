package com.sheldondesousa.uncork.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Wine

@Composable
fun AppHeader(
    title: String,
    icon: ImageVector,
    onIconClick: (() -> Unit)? = null,
    iconContentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    dividerInset: Dp = 22.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onIconClick == null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(26.dp),
                    tint = Wine,
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onIconClick)
                        .semantics { contentDescription = iconContentDescription.orEmpty() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = Wine,
                    )
                }
            }
            Spacer(Modifier.width(if (onIconClick == null) 9.dp else 3.dp))
            Text(
                text = title,
                color = Ink,
                fontSize = 24.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "Uncork",
                color = Ink,
                fontSize = 30.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "AI SOMMELIER",
                color = Wine,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
        }
    }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dividerInset)
                .height(1.dp)
                .background(Hairline),
        )
    }
}
