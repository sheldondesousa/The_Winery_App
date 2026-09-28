package com.sheldondesousa.uncork.eval

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sheldondesousa.uncork.BuildConfig
import kotlinx.coroutines.launch
import java.io.File

private sealed interface EvalRunState {
    data object Idle : EvalRunState
    data class Running(val completed: Int, val total: Int, val lastCaseId: String) : EvalRunState
    data class Done(val file: File) : EvalRunState
    data class Failed(val message: String) : EvalRunState
}

/**
 * Debug-only screen that kicks off [GemmaEvalRunner] against the bundled 50-conversation test
 * set and reports progress. Only ever reachable when [BuildConfig.DEBUG] is true — see
 * [com.sheldondesousa.uncork.ui.landing.LandingRoute]'s debug entry point.
 */
@Composable
internal fun GemmaEvalDebugScreen(onBack: () -> Unit) {
    check(BuildConfig.DEBUG) { "GemmaEvalDebugScreen must never be reachable in a release build." }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EvalRunState>(EvalRunState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Gemma Eval Runner", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Runs every conversation in the bundled test set through the real Gemma chat path, " +
                "3 times each. This can take a while — progress is also logged to Logcat under " +
                "tag \"GemmaEval\".",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val current = state) {
            is EvalRunState.Idle -> Button(onClick = {
                state = EvalRunState.Running(0, 0, "")
                scope.launch {
                    runCatching {
                        GemmaEvalRunner.run(context) { progress ->
                            state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                        }
                    }.onSuccess { file -> state = EvalRunState.Done(file) }
                        .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                }
            }) { Text("Run eval") }

            is EvalRunState.Running -> Text(
                if (current.total > 0) {
                    "Running ${current.completed} / ${current.total} — last: ${current.lastCaseId}"
                } else {
                    "Loading test set and preparing model…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            is EvalRunState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Done. Wrote:\n${current.file.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { shareEvalResult(context, current.file) }) { Text("Share results file") }
                TextButton(onClick = { state = EvalRunState.Idle }) { Text("Run again") }
            }

            is EvalRunState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Failed: ${current.message}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { state = EvalRunState.Idle }) { Text("Try again") }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun shareEvalResult(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.evalfileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share eval results"))
}
