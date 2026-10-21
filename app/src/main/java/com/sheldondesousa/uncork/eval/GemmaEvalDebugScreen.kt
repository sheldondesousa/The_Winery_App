package com.sheldondesousa.uncork.eval

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed interface EvalRunState {
    data object Idle : EvalRunState
    data class Running(val completed: Int, val total: Int, val lastCaseId: String) : EvalRunState
    data class Done(val file: File) : EvalRunState
    data class Failed(val message: String) : EvalRunState
}

/**
 * Debug-only screen for bottle ASK and retained card evaluations. Reports progress. Only ever reachable when [BuildConfig.DEBUG] is true — see
 * [com.sheldondesousa.uncork.ui.landing.LandingRoute]'s debug entry point.
 */
@Composable
internal fun GemmaEvalDebugScreen(onBack: () -> Unit,
    bottle: BottleAskEvalRunner.BottleFactory? = null,
) {
    check(BuildConfig.DEBUG) { "GemmaEvalDebugScreen must never be reachable in a release build." }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EvalRunState>(EvalRunState.Idle) }
    val stage1Controller = remember { EvalRunController() }
    var stage1Job by remember { mutableStateOf<Job?>(null) }
    var stage1Paused by remember { mutableStateOf(false) }

    // A 150-generation run easily outlasts the screen timeout. Once the screen sleeps (or the
    // app is switched away from), Android freezes this process entirely — not a crash, every
    // thread including this coroutine just pauses — until it's foregrounded again. Keeping the
    // screen on for the run's duration is what actually prevents that; it does NOT survive the
    // user manually locking the phone (e.g. pressing the power button) or switching apps.
    val isRunning = state is EvalRunState.Running
    DisposableEffect(isRunning) {
        val activity = context.findActivity()
        if (isRunning) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
            "Choose a bottle-card evaluation or the first bottle detail ASK evaluation.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val current = state) {
            is EvalRunState.Idle -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    state = EvalRunState.Running(0, 0, "")
                    stage1Controller.paused = false
                    stage1Paused = false
                    stage1Job = scope.launch {
                        runCatching {
                            GemmaCardsDetailEvalRunner.run(context, stage1Controller) { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error ->
                                state = if (error is CancellationException) {
                                    EvalRunState.Failed("Cancelled. Results so far are kept in the eval folder.")
                                } else {
                                    EvalRunState.Failed(error.message ?: error.javaClass.simpleName)
                                }
                            }
                    }
                }) { Text("Run Stage 1 (Eval A + B, 1 pass)") }
                Button(onClick = {
                    state = EvalRunState.Running(0, 0, "")
                    stage1Controller.paused = false
                    stage1Paused = false
                    stage1Job = scope.launch {
                        runCatching {
                            GemmaCardsDetailEvalRunner.run(context, stage1Controller, setOf("B")) { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error ->
                                state = if (error is CancellationException) {
                                    EvalRunState.Failed("Cancelled. Results so far are kept in the eval folder.")
                                } else {
                                    EvalRunState.Failed(error.message ?: error.javaClass.simpleName)
                                }
                            }
                    }
                }) { Text("Run Eval B only (100 cases)") }
                if (bottle != null) Button(onClick = {
                    state = EvalRunState.Running(0, 100, "")
                    stage1Job = scope.launch {
                        runCatching {
                            BottleAskEvalRunner.run(context, bottle) { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                    }
                }) { Text("Run bottle detail ASK v3 (100 questions)") }
                if (bottle != null) Button(onClick = {
                    state = EvalRunState.Running(0, 300, "ASK4")
                    stage1Job = scope.launch {
                        runCatching {
                            BottleAskEvalRunner.runBroadBatch(context, bottle) { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                    }
                }) { Text("Run ASK v4: 100 questions × 3 runs") }
                if (bottle != null) Button(onClick = {
                    state = EvalRunState.Running(0, 300, "ASK5")
                    stage1Job = scope.launch {
                        runCatching {
                            BottleAskEvalRunner.runOnce(context, bottle, "bottle_ask_v5.json") { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                    }
                }) { Text("Run ASK v5: 300 held-out questions × 1 run") }
                if (bottle != null) Button(onClick = {
                    state = EvalRunState.Running(0, 300, "ASK6")
                    stage1Job = scope.launch {
                        runCatching {
                            BottleAskEvalRunner.runOnce(context, bottle, "bottle_ask_v6.json") { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                    }
                }) { Text("Run ASK v6: 300 grape-knowledge questions × 1 run") }
                if (bottle != null) Button(onClick = {
                    state = EvalRunState.Running(0, 400, "ASK7")
                    stage1Job = scope.launch {
                        runCatching {
                            BottleAskEvalRunner.runOnce(context, bottle, "bottle_ask_v7.json") { progress ->
                                state = EvalRunState.Running(progress.completed, progress.total, progress.lastCaseId)
                            }
                        }.onSuccess { file -> state = EvalRunState.Done(file) }
                            .onFailure { error -> state = EvalRunState.Failed(error.message ?: error.javaClass.simpleName) }
                    }
                }) { Text("Run ASK v7: 400 answerable, false-claim and no-data questions × 1 run") }
            }

            is EvalRunState.Running -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (current.total > 0) {
                        "${if (stage1Paused) "Paused" else "Running"} ${current.completed} / ${current.total} — last: ${current.lastCaseId}"
                    } else {
                        "Loading test set and preparing model…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (stage1Job != null) {
                    if (!current.lastCaseId.startsWith("ASK") && current.total != 100) Button(onClick = {
                        stage1Paused = !stage1Paused
                        stage1Controller.paused = stage1Paused
                    }) { Text(if (stage1Paused) "Resume" else "Pause after this case") }
                    TextButton(onClick = { stage1Job?.cancel() }) { Text("Cancel run") }
                }
            }

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

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
