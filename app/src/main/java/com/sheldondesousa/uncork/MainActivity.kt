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
import androidx.compose.runtime.rememberCoroutineScope
import com.sheldondesousa.uncork.ui.guided.GuidedResult
import com.sheldondesousa.uncork.ui.guided.GuidedSelectionScreen
import com.sheldondesousa.uncork.ui.guided.GuidedSelectionState
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
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
import com.sheldondesousa.uncork.ui.favorites.FavoritesRoute
import com.sheldondesousa.uncork.ui.favorites.FavoritesRepository
import com.sheldondesousa.uncork.ui.landing.LandingRoute
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
                var selectedTab by remember { mutableStateOf<AppTab?>(null) }
                var favorites by remember { mutableStateOf(favoritesRepository.load()) }
                val conversationState = rememberConversationSessionState()
                val guidedScope = rememberCoroutineScope()
                val guidedState = remember {
                    GuidedSelectionState(
                        scope = guidedScope,
                        gemmaSearch = gemmaResponder::guidedSelection,
                        databaseSearch = { criteria ->
                            val result = wineReviewRepository.findGuided(criteria)
                            GuidedResult.Complete(
                                cards = result.reviews.map { match ->
                                    val review = match.review
                                    WineSuggestion(
                                        name = review.name, winery = review.winery,
                                        country = review.country, province = review.province,
                                        variety = review.variety, rating = review.points,
                                        reviewSummary = review.reviewSummary,
                                        wineType = match.wineType, sweetness = match.sweetness,
                                        tannin = match.tannin, acidity = match.acidity, body = match.body,
                                        source = WineSuggestionSource.KAGGLE,
                                        requestContext = criteria.description, profileComplete = true,
                                    )
                                },
                                usedProvinceFallback = result.usedProvinceFallback,
                            )
                        },
                        reportDatabaseError = { error ->
                            Log.w("GuidedSelection", "Database search failed.", error)
                        },
                    )
                }
                val onTabSelected: (AppTab) -> Unit = { tab ->
                    selectedTab = tab
                }
                val onBackToLanding: () -> Unit = {
                    selectedTab = null
                }

                if (modelReady) {
                    when (selectedTab) {
                        null -> LandingRoute(onTabSelected = onTabSelected)
                        AppTab.Find -> GuidedSelectionScreen(
                            state = guidedState,
                            onSuggestionClick = { stageWine = it.toStageWine() },
                            onTabSelected = onTabSelected,
                            onModelSetup = { modelReady = false },
                            onBack = onBackToLanding,
                        )
                        AppTab.Conversation -> ConversationRoute(
                            responder = conversationResponder,
                            state = conversationState,
                            onSuggestionClick = { stageWine = it.toStageWine() },
                            onBack = onBackToLanding,
                        )
                        AppTab.Favorites -> FavoritesRoute(
                            favorites = favorites,
                            onFavoriteClick = { stageWine = it.toStageWine() },
                            onBack = onBackToLanding,
                        )
                    }
                    stageWine?.let { wine ->
                        StageShowRoute(
                            wine = wine,
                            onBack = { stageWine = null },
                            loadProfile = { suggestion ->
                                if (suggestion.source == WineSuggestionSource.GEMMA && !suggestion.profileComplete) {
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
