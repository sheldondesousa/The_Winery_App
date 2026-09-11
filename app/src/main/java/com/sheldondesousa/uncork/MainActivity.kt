package com.sheldondesousa.uncork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.ConversationRoute
import com.sheldondesousa.uncork.ui.conversation.rememberConversationSessionState
import com.sheldondesousa.uncork.ui.history.HistoryRepository
import com.sheldondesousa.uncork.ui.history.HistoryRoute
import com.sheldondesousa.uncork.ui.favorites.FavoritesRoute
import com.sheldondesousa.uncork.ui.favorites.FavoritesRepository
import com.sheldondesousa.uncork.ui.splash.SplashRoute
import com.sheldondesousa.uncork.ui.stageshow.StageShowRoute
import com.sheldondesousa.uncork.ui.stageshow.StageWine
import com.sheldondesousa.uncork.ui.stageshow.toStageWine
import com.sheldondesousa.uncork.ui.stageshow.toWineSuggestion
import com.sheldondesousa.uncork.ui.theme.UncorkTheme

class MainActivity : ComponentActivity() {
    private lateinit var gemmaResponder: GemmaConversationResponder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        val modelFileManager = ModelFileManager(applicationContext)
        val historyRepository = HistoryRepository(applicationContext)
        val favoritesRepository = FavoritesRepository(applicationContext)
        gemmaResponder = GemmaConversationResponder(applicationContext, modelFileManager.modelFile)
        setContent {
            UncorkTheme {
                var modelReady by remember { mutableStateOf(false) }
                var stageWine by remember { mutableStateOf<StageWine?>(null) }
                var selectedTab by remember { mutableStateOf(AppTab.Conversation) }
                var historyEntries by remember { mutableStateOf(historyRepository.load()) }
                var favorites by remember { mutableStateOf(favoritesRepository.load()) }
                val conversationState = rememberConversationSessionState()
                val onTabSelected: (AppTab) -> Unit = { tab ->
                    selectedTab = tab
                }

                if (modelReady) {
                    when (selectedTab) {
                        AppTab.Conversation -> ConversationRoute(
                            responder = gemmaResponder,
                            state = conversationState,
                            onSuggestionClick = { stageWine = it.toStageWine() },
                            onSuggestionRecorded = { suggestion, request ->
                                historyEntries = historyRepository.record(suggestion, request)
                            },
                            onTabSelected = onTabSelected,
                        )
                        AppTab.History -> HistoryRoute(
                            entries = historyEntries,
                            onEntryClick = { stageWine = it.suggestion.toStageWine() },
                            onDeleteEntries = { entryIds ->
                                historyEntries = historyRepository.delete(entryIds)
                            },
                            onTabSelected = onTabSelected,
                        )
                        AppTab.Favorites -> FavoritesRoute(
                            favorites = favorites,
                            onFavoriteClick = { stageWine = it.toStageWine() },
                            onTabSelected = onTabSelected,
                        )
                    }
                    stageWine?.let { wine ->
                        StageShowRoute(
                            wine = wine,
                            onBack = { stageWine = null },
                            initiallyFavorite = favoritesRepository.contains(wine.toWineSuggestion()),
                            onFavorite = { favorites = favoritesRepository.add(it) },
                        )
                    }
                } else {
                    SplashRoute(
                        modelFileManager = modelFileManager,
                        onModelReady = { modelReady = true },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        gemmaResponder.close()
        super.onDestroy()
    }
}
