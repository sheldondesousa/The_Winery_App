package com.sheldondesousa.uncork.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.ResultCardBackground

/** A short pulsing placeholder shown in place of result cards while a search is running. */
@Composable
fun ResultSkeleton(count: Int = 3, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "result-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "result-skeleton-pulse",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("result-skeleton")
            .semantics { contentDescription = "Loading results" },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(count) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ResultCardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(16.dp)
                    .alpha(pulse),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBar(widthFraction = 0.35f, height = 8)
                SkeletonBar(widthFraction = 0.8f, height = 16)
                SkeletonBar(widthFraction = 0.25f, height = 8)
                SkeletonBar(widthFraction = 0.55f, height = 12)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Int) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp)
            .background(InkSubtle.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    )
}
