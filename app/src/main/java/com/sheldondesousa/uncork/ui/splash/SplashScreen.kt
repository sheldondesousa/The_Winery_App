package com.sheldondesousa.uncork.ui.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine
import kotlinx.coroutines.flow.catch

private sealed interface SplashUiState {
    data class Loading(val progress: Float?) : SplashUiState
    data class Error(val attempts: Int) : SplashUiState
}

@Composable
fun SplashRoute(
    modelLoader: ModelLoader,
    onModelReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempts by remember { mutableIntStateOf(0) }
    var loadRequest by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<SplashUiState>(SplashUiState.Loading(0f)) }

    LaunchedEffect(loadRequest) {
        state = SplashUiState.Loading(0f)
        modelLoader.load()
            .catch {
                attempts += 1
                state = SplashUiState.Error(attempts)
            }
            .collect { event ->
                when (event) {
                    is ModelLoadEvent.Progress -> state = SplashUiState.Loading(event.fraction)
                    ModelLoadEvent.Ready -> onModelReady()
                }
            }
    }

    SplashScreen(
        state = state,
        onRetry = { loadRequest += 1 },
        modifier = modifier,
    )
}

@Composable
private fun SplashScreen(
    state: SplashUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Uncork",
            color = Ink,
            fontSize = 64.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 70.sp,
        )
        Text(
            text = "AI SOMMELIER",
            color = Wine,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )

        Spacer(Modifier.height(72.dp))

        when (state) {
            is SplashUiState.Loading -> LoadingState(progress = state.progress)
            is SplashUiState.Error -> ErrorState(
                attempts = state.attempts,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun LoadingState(progress: Float?) {
    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("model-load-progress"),
                color = Wine,
                trackColor = Ink.copy(alpha = 0.10f),
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 120),
                label = "model load progress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("model-load-progress"),
                color = Wine,
                trackColor = Ink.copy(alpha = 0.10f),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                color = InkMuted,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ErrorState(attempts: Int, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (attempts >= 3) {
                "Model failed to load three times. Check your device storage or compatibility and try again."
            } else {
                "The sommelier couldn’t get ready. Let’s try that again."
            },
            color = InkMuted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = Wine),
        ) {
            Text(
                text = "Retry",
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

