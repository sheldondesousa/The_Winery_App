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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.model.ModelDownloadEvent
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkMuted
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface SplashUiState {
    data object Checking : SplashUiState
    data object TokenRequired : SplashUiState
    data class Downloading(val progress: Float) : SplashUiState
    data object Verifying : SplashUiState
    data class Error(val message: String) : SplashUiState
}

@Composable
fun SplashRoute(
    modelFileManager: ModelFileManager,
    onModelReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<SplashUiState>(SplashUiState.Checking) }
    var accessToken by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(modelFileManager) {
        if (modelFileManager.isModelReady()) onModelReady() else state = SplashUiState.TokenRequired
    }

    fun startDownload() {
        if (accessToken.isBlank()) return
        scope.launch {
            var lastError = "The model could not be downloaded."
            repeat(MAX_AUTOMATIC_ATTEMPTS) { attempt ->
                state = SplashUiState.Downloading(0f)
                val result = runCatching {
                    modelFileManager.download(accessToken).collect { event ->
                        when (event) {
                            is ModelDownloadEvent.Progress -> state = SplashUiState.Downloading(event.fraction)
                            ModelDownloadEvent.Verifying -> state = SplashUiState.Verifying
                            ModelDownloadEvent.Ready -> onModelReady()
                        }
                    }
                }
                if (result.isSuccess) return@launch
                lastError = result.exceptionOrNull()?.message ?: lastError
                if (attempt < MAX_AUTOMATIC_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
            }
            state = SplashUiState.Error(lastError)
        }
    }

    SplashScreen(
        state = state,
        accessToken = accessToken,
        onAccessTokenChange = { accessToken = it },
        onDownload = ::startDownload,
        onRetry = ::startDownload,
        onChangeToken = {
            accessToken = ""
            state = SplashUiState.TokenRequired
        },
        modifier = modifier,
    )
}

@Composable
private fun SplashScreen(
    state: SplashUiState,
    accessToken: String,
    onAccessTokenChange: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onChangeToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Parchment)
            .imePadding()
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
        Spacer(Modifier.height(64.dp))

        when (state) {
            SplashUiState.Checking -> LoadingState(null, "Checking local model…")
            SplashUiState.TokenRequired -> TokenEntryState(
                token = accessToken,
                onTokenChange = onAccessTokenChange,
                onDownload = onDownload,
            )
            is SplashUiState.Downloading -> LoadingState(
                progress = state.progress,
                label = "Downloading Gemma 4 E2B",
            )
            SplashUiState.Verifying -> LoadingState(null, "Verifying model…")
            is SplashUiState.Error -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                onChangeToken = onChangeToken,
            )
        }
    }
}

@Composable
private fun TokenEntryState(
    token: String,
    onTokenChange: (String) -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Download the on-device model once, then use Uncork fully offline.",
            color = InkMuted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth().testTag("hugging-face-token"),
            label = { Text("Hugging Face access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The token is used only for this download and is not saved.",
            color = InkMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onDownload,
            enabled = token.isNotBlank(),
            colors = ButtonDefaults.textButtonColors(contentColor = Wine),
        ) {
            Text("Download model", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingState(progress: Float?, label: String) {
    Column(
        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = InkMuted,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).testTag("model-load-progress"),
                color = Wine,
                trackColor = Ink.copy(alpha = 0.10f),
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 120),
                label = "model download progress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(2.dp).testTag("model-load-progress"),
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
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onChangeToken: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Model failed to download three times. $message",
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
            Text("Retry", fontWeight = FontWeight.SemiBold)
        }
        TextButton(
            onClick = onChangeToken,
            colors = ButtonDefaults.textButtonColors(contentColor = InkMuted),
        ) {
            Text("Use a different token")
        }
    }
}

private const val MAX_AUTOMATIC_ATTEMPTS = 3
private const val RETRY_DELAY_MILLIS = 1_000L
