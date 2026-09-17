package com.sheldondesousa.uncork

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.sheldondesousa.uncork.data.profile.VarietyRegionDatabase
import com.sheldondesousa.uncork.data.profile.VarietyRegionProfileRepository
import com.sheldondesousa.uncork.data.profile.seedFromAssetsIfEmpty
import com.sheldondesousa.uncork.data.reviews.WineReviewRepository
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.BraveSearchHttpClient
import com.sheldondesousa.uncork.model.BraveWineWebSearchDataSource
import com.sheldondesousa.uncork.model.KaggleConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.ConversationRoute
import com.sheldondesousa.uncork.ui.conversation.rememberConversationSessionState
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
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
import kotlinx.coroutines.launch

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
        val varietyRegionDatabase = VarietyRegionDatabase.getInstance(applicationContext)
        val wineReviewRepository = WineReviewRepository(applicationContext)
        val wineOptionCache = VarietyRegionProfileRepository(
            varietyRegionDatabase.varietyRegionProfileDao(),
        )
        val profileSeedJob = lifecycleScope.launch {
            seedFromAssetsIfEmpty(applicationContext, varietyRegionDatabase)
        }
        val reviewDatabaseJob = lifecycleScope.launch {
            runCatching { wineReviewRepository.prepare() }
                .onFailure { error ->
                    Log.w("WineReviewDatabase", "Kaggle review database could not be prepared.", error)
                }
        }
        gemmaResponder = GemmaConversationResponder(applicationContext, modelFileManager.modelFile)
        val webSearch = if (BuildConfig.BRAVE_SEARCH_API_KEY.isBlank()) {
            com.sheldondesousa.uncork.model.UnavailableWineWebSearchDataSource
        } else {
            BraveWineWebSearchDataSource(
                client = BraveSearchHttpClient(BuildConfig.BRAVE_SEARCH_API_KEY),
                synthesizer = gemmaResponder::synthesizeWebResults,
            )
        }
        val conversationResponder = KaggleConversationResponder(
            gemmaResponder = gemmaResponder,
            wineReviewRepository = wineReviewRepository,
            optionCache = wineOptionCache,
            webSearch = webSearch,
        )
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
                            responder = conversationResponder,
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
                            loadProfile = { suggestion ->
                                if (suggestion.source == WineSuggestionSource.GEMMA) {
                                    gemmaResponder.loadProfile(suggestion)
                                } else {
                                    suggestion
                                }
                            },
                            initiallyFavorite = favoritesRepository.contains(wine.toWineSuggestion()),
                            onFavoriteChange = { suggestion, selected ->
                                favorites = if (selected) {
                                    favoritesRepository.add(suggestion)
                                } else {
                                    favoritesRepository.remove(suggestion)
                                }
                            },
                        )
                    }
                } else {
                    SplashRoute(
                        modelFileManager = modelFileManager,
                        prepareApp = {
                            runCatching { gemmaResponder.prepare() }
                                .onFailure { error ->
                                    Log.w(
                                        "GemmaPreparation",
                                        "Gemma could not be prepared before chat opened.",
                                        error,
                                    )
                                }
                            profileSeedJob.join()
                            reviewDatabaseJob.join()
                        },
                        onReady = { modelReady = true },
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
