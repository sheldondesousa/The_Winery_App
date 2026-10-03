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
import com.sheldondesousa.uncork.ui.guided.ReviewsPage
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.sheldondesousa.uncork.data.knowledge.GrapeKnowledgeBase
import com.sheldondesousa.uncork.data.profile.VarietyRegionDatabase
import com.sheldondesousa.uncork.data.profile.VarietyRegionProfileRepository
import com.sheldondesousa.uncork.data.profile.seedFromAssetsIfEmpty
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.data.reviews.WineReviewRepository
import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import com.sheldondesousa.uncork.eval.GemmaEvalDebugScreen
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.BraveSearchHttpClient
import com.sheldondesousa.uncork.model.BraveWineWebSearchDataSource
import com.sheldondesousa.uncork.model.WineWebSearchRequest
import com.sheldondesousa.uncork.model.toCacheSuggestion
import com.sheldondesousa.uncork.model.KaggleConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.model.WineAskContext
import com.sheldondesousa.uncork.model.WinePreferences
import com.sheldondesousa.uncork.model.toGuidedCriteria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sheldondesousa.uncork.model.WineFactsNote
import com.sheldondesousa.uncork.ui.conversation.AppTab
import com.sheldondesousa.uncork.ui.conversation.ConversationRoute
import com.sheldondesousa.uncork.ui.conversation.ConversationSessionState
import com.sheldondesousa.uncork.ui.conversation.WineAskChat
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

/** One open "Ask" conversation: the wine it is about, its Gemma session and its on-screen messages. */
private class AskSession(
    val wine: StageWine,
    val discussion: GemmaConversationResponder.WineDiscussion,
    val state: ConversationSessionState,
)

class MainActivity : ComponentActivity() {
    private lateinit var gemmaResponder: GemmaConversationResponder

    /** Set once the UI exists; lets Chat hand its finished answers to the shared Results page. */
    @Volatile
    private var resultsHandoff: (suspend (WinePreferences) -> Boolean)? = null

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
        val askContext = WineAskContext(GrapeKnowledgeBase.load(applicationContext), wineOptionCache, wineReviewRepository)
        val conversationResponder = KaggleConversationResponder(
            gemmaResponder = gemmaResponder,
            wineReviewRepository = wineReviewRepository,
            optionCache = wineOptionCache,
            webSearch = webSearch,
            onResultsReady = { preferences -> resultsHandoff?.invoke(preferences) ?: false },
        )
        setContent {
            UncorkTheme {
                var modelReady by remember { mutableStateOf(false) }
                var stageWine by remember { mutableStateOf<StageWine?>(null) }
                var askSession by remember { mutableStateOf<AskSession?>(null) }
                val askScope = rememberCoroutineScope()
                fun closeAsk() {
                    askSession?.discussion?.close()
                    askSession = null
                }
                var selectedTab by remember { mutableStateOf<AppTab?>(null) }
                var showEvalDebug by remember { mutableStateOf(false) }
                val favorites = remember { favoritesRepository.load() }
                val conversationState = rememberConversationSessionState()
                val guidedScope = rememberCoroutineScope()
                val guidedState = remember {
                    GuidedSelectionState(
                        scope = guidedScope,
                        reviewsSearch = { criteria, band, offset, dropProvince, seed ->
                            val page = wineReviewRepository.findGuidedPage(criteria, band, offset, seed, dropProvince)
                            ReviewsPage(
                                cards = page.reviews.map { match ->
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
                                hasMore = page.hasMore,
                                usedProvinceFallback = page.usedProvinceFallback,
                            )
                        },
                        extendedSearch = { criteria ->
                            // Previously saved web results, matched on the same selections. The cache
                            // stores one variety string per row, so each merged database name is tried.
                            // It has no sweetness data, so a sweetness choice can't be honoured here.
                            val varietyNames = if (criteria.variety.isBlank()) listOf(null)
                            else GrapeVarieties.all.firstOrNull { it.name == criteria.variety }
                                ?.databaseNames.orEmpty()
                            val cards = if (criteria.sweetness.isNotBlank()) emptyList() else
                                varietyNames.flatMap { variety ->
                                    wineOptionCache.findMatching(
                                        WineSelectionCriteria(
                                            wineType = criteria.wineType.ifBlank { null },
                                            country = criteria.country.ifBlank { null },
                                            province = criteria.province.ifBlank { null },
                                            variety = variety,
                                            body = criteria.body.ifBlank { null },
                                            tannin = criteria.tannin.ifBlank { null },
                                            acidity = criteria.acidity.ifBlank { null },
                                        ),
                                    )
                                }.distinctBy { it.name.lowercase() to it.country.lowercase() to it.province.lowercase() }
                                    .take(3)
                                    .map { it.toCacheSuggestion() }
                            GuidedResult.Complete(cards)
                        },
                        reportDatabaseError = { error ->
                            Log.w("GuidedSelection", "Database search failed.", error)
                        },
                        webSearch = { criteria ->
                            // A missing key is a failure the user can see ("could not be completed"),
                            // not a silent "No results found".
                            check(BuildConfig.BRAVE_SEARCH_API_KEY.isNotBlank()) {
                                "Brave Search API key is not configured."
                            }
                            webSearch.search(
                                WineWebSearchRequest(
                                    originalQuery = criteria.webQuery,
                                    gemmaSuggestions = emptyList(),
                                ),
                            ).filter { it.name.isNotBlank() && !it.name.equals("Unknown", ignoreCase = true) }
                                .map { option ->
                                    option.copy(
                                        summary = "Unknown",
                                        reviewSummary = "Unknown",
                                        source = WineSuggestionSource.WEB_SEARCH,
                                        requestContext = option.requestContext ?: criteria.description,
                                    )
                                }
                        },
                    )
                }
                androidx.compose.runtime.SideEffect {
                    resultsHandoff = { preferences ->
                        val criteria = preferences.toGuidedCriteria()
                        if (criteria == null) {
                            false
                        } else {
                            withContext(Dispatchers.Main) {
                                guidedState.selection = criteria
                                guidedState.search()
                                selectedTab = AppTab.Find
                            }
                            true
                        }
                    }
                }
                val onTabSelected: (AppTab) -> Unit = { tab ->
                    if (selectedTab == null && tab == AppTab.Find) {
                        guidedState.backToForm()
                    }
                    selectedTab = tab
                }
                val onBackToLanding: () -> Unit = {
                    selectedTab = null
                }

                if (modelReady && showEvalDebug) {
                    GemmaEvalDebugScreen(onBack = { showEvalDebug = false })
                } else if (modelReady) {
                    when (selectedTab) {
                        null -> LandingRoute(
                            onTabSelected = onTabSelected,
                            onOpenEvalDebug = { showEvalDebug = true },
                        )
                        AppTab.Find -> GuidedSelectionScreen(
                            state = guidedState,
                            onSuggestionClick = { stageWine = it.toStageWine() },
                            onTabSelected = onTabSelected,
                            onBack = onBackToLanding,
                            onHome = onBackToLanding,
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
                        val ask = askSession
                        if (ask != null && ask.wine === wine) {
                            // Open conversation about this wine — not the Find-a-wine chat.
                            ConversationRoute(
                                responder = ask.discussion,
                                state = ask.state,
                                title = WineAskChat.TITLE,
                                onBack = ::closeAsk,
                            )
                        } else {
                            StageShowRoute(
                                wine = wine,
                                onBack = { stageWine = null },
                                onHome = {
                                    closeAsk()
                                    stageWine = null
                                    selectedTab = null
                                },
                                loadDetails = if (
                                    wine.ai.source == WineSuggestionSource.GEMMA &&
                                    !wine.ai.profileComplete
                                ) gemmaResponder::enrichWineDetails else null,
                                onAsk = {
                                    askScope.launch {
                                        val suggestion = wine.toWineSuggestion()
                                        val facts = askContext.factsFor(suggestion)
                                        closeAsk()
                                        val discussion = gemmaResponder.startWineDiscussion(facts, WineFactsNote.reminder(suggestion))
                                        askSession = AskSession(
                                            wine = wine,
                                            discussion = discussion,
                                            state = ConversationSessionState(
                                                WineAskChat.initialMessages(suggestion.name),
                                            ),
                                        )
                                        // Gemma reads the wine's facts while the user reads the welcome and types.
                                        discussion.warmUp()
                                    }
                                },
                            )
                        }
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
