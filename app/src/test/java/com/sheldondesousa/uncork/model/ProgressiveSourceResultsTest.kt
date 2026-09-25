package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.ConversationStreamUpdate
import com.sheldondesousa.uncork.ui.conversation.SourceQueryStatus
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressiveSourceResultsTest {
    @Test
    fun otherSourcesAndFinalCompletionRetainPublishedProfiles() {
        val updates = mutableListOf<ConversationStreamUpdate>()
        val progress = KaggleConversationResponder.SourceProgress { updates += it }
        val wine = WineSuggestion(
            name = "First wine", province = "Bordeaux", summary = "A structured red.",
            flavorNotes = "Blackcurrant, cedar", profileComplete = true,
        )
        progress.startLoading(WineSuggestionSource.GEMMA, WineSuggestionSource.KAGGLE)
        progress.partial(WineSuggestionSource.GEMMA, listOf(wine))
        progress.resolve(WineSuggestionSource.KAGGLE, emptyList())
        progress.startLoading(WineSuggestionSource.CACHE)
        progress.resolve(WineSuggestionSource.CACHE, emptyList())
        val partial = updates.last().sourceResults.single { it.source == WineSuggestionSource.GEMMA }
        assertEquals(SourceQueryStatus.LOADING, partial.status)
        assertEquals(listOf(wine), partial.suggestions)
        assertEquals(partial, progress.resultsWithLoading.single { it.source == WineSuggestionSource.GEMMA })
        progress.resolve(WineSuggestionSource.GEMMA, listOf(wine))
        val complete = progress.results.single { it.source == WineSuggestionSource.GEMMA }
        assertEquals(SourceQueryStatus.COMPLETE, complete.status)
        assertEquals(listOf(wine), complete.suggestions)
    }
}
