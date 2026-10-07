package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.data.knowledge.InternalGrapeProfile
import com.sheldondesousa.uncork.data.knowledge.WineProduction
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import com.sheldondesousa.uncork.data.reviews.CountryReviewCount
import com.sheldondesousa.uncork.data.reviews.GrapeVarietyLookup
import com.sheldondesousa.uncork.data.reviews.GrapeWineriesSample
import com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest
import com.sheldondesousa.uncork.data.reviews.WineryWine
import com.sheldondesousa.uncork.data.reviews.WineryWinesSample

/** Extra context for one question in a chat: added in front of the question only when the question needs it. */
interface AskExtras {
    /** Blocks to add before [query], or null when the question needs nothing extra. */
    suspend fun forQuestion(query: String): String?

    /**
     * A finished list of wineries when the question asks for one ("list 5 wineries for Sangiovese in Italy"), written by
     * the app rather than by Gemma, which names only a few of the wineries it is given. Null when the question is not a list request.
     */
    suspend fun wineryList(query: String): String? = null

    /**
     * What to add to [query], or, when the question names something the app should know and nothing was found, the
     * finished refusal. The default adds [forQuestion]'s text and never refuses.
     */
    suspend fun lookup(query: String): AskLookup = AskLookup(forQuestion(query))

    /** Forget what has been sent. Called when the conversation is rebuilt and so has lost earlier blocks. */
    fun reset()
}

/** The outcome of looking a question up: context for Gemma, or the app's own refusal (Gemma is then not called). */
class AskLookup(val context: String?, val refusal: String? = null)

/**
 * Decides when a question that retrieved nothing gets the app's fixed refusal instead of going to Gemma: it names
 * something specific the app should know (a winery, wine, place) or uses winery or production wording, and it is not a
 * greeting, thanks, out-of-scope or a safety matter, which Gemma handles.
 */
object RefusalGate {
    private val EXEMPT = Regex(
        // greetings and thanks
        "^\\W*(hi|hello|hey|howdy|good\\s+(morning|afternoon|evening)|thanks?|thank\\s+you|cheers|bye|goodbye|ok|okay|great|cool)\\b|" +
            // out of scope: food, other drinks, recipes, health
            "\\b(pair|pairs|pairing|pairings|goes\\s+with|go\\s+with|dish|recipe|cook|cooking|cheese|food|dinner|lunch|steak|beer|whisk(e)?y|vodka|gin|rum|tequila|cocktail|spirits?|liquor|medical|health|doctor|pregnan\\w*|medicine|diet|calories)\\b|" +
            // safety: abuse, minors, the model and its instructions
            "\\b(system\\s+prompt|instructions?|ignore\\s+(all|previous|your)|reveal|jailbreak|what\\s+model|which\\s+model|who\\s+(made|built|created)\\s+you|underage|minors?|kids?|children|teen\\w*|stupid|idiot|shut\\s+up)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val QUESTION_STARTERS = setOf(
        "what", "whats", "which", "who", "whose", "where", "when", "why", "how", "is", "are", "was", "does", "do", "did", "can", "could",
        "would", "should", "tell", "name", "list", "give", "show", "describe", "explain", "i", "my", "the", "a", "an", "any", "and", "or",
        "but", "so", "yes", "no", "please", "uncork", "hi", "hello", "hey", "thanks", "thank",
    )

    /** True for a question that names something specific: a capitalised word that is not a question word, grape or country. */
    private fun namesSomethingSpecific(query: String, known: Set<String>): Boolean =
        query.split(Regex("\\s+")).map { it.trim(',', '.', '?', '!', ';', ':', '"', '\'', '(', ')') }
            .any { w ->
                w.length >= 3 && w[0].isUpperCase() && w.lowercase().let { it !in QUESTION_STARTERS && it !in known } &&
                    GrapeProfileInternal.normalize(w).let { n -> n !in known && CountryMentions.find(n).isEmpty() && GrapeVarietyLookup.findMentioned(n).isEmpty() }
            }

    /** [known] holds words that are already about the open bottle (its name, winery, grape, place), which are not new names. */
    fun shouldRefuse(query: String, known: Set<String> = emptySet()): Boolean {
        if (EXEMPT.containsMatchIn(query)) return false
        return WineryIntent.asksAboutWineries(query) || ProductionIntent.asksHowMade(query) || namesSomethingSpecific(query, known)
    }
}

/** Spots questions that ask what other people think, as opposed to questions about the open wine. */
object ReviewIntent {
    private val PATTERN = Regex(
        "\\b(reviews?|reviewers?|reviewed|critics?|enthusiasts?|opinions?|feedback|consensus)\\b|" +
            "\\btell\\s+me\\s+what\\s+(people|folks|others|everyone|everybody|they|reviewers?|critics?|experts|enthusiasts|drinkers|wine\\s+lovers)\\b|" +
            "\\bwhat\\s+(do|does|are|did|have)\\s+(people|folks|others|everyone|everybody|wine\\s+lovers|experts)\\b.*\\b(say|saying|said|think|thinking|thought)\\b|" +
            "\\bwhat\\s+(is|are)\\s+(people|the\\s+critics)\\s+saying\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksForReviews(query: String): Boolean = PATTERN.containsMatchIn(query)
}

/** Spots questions that ask for a description of a grape ("tell me about it", "describe it"), with or without naming it. */
object GrapeIntent {
    private val PATTERN = Regex("\\btell\\s+me\\s+(more|about)\\b|\\bdescribe\\b|\\bdescription\\b", RegexOption.IGNORE_CASE)
    fun asksToDescribe(query: String): Boolean = PATTERN.containsMatchIn(query)
}

/** Spots questions about how a wine or grape is made (harvest, fermentation, ageing, blending and so on). */
object ProductionIntent {
    private val PATTERN = Regex(
        "\\bhow\\b.*\\b(made|make|produced?|processed?|manufactured?|vinified|harvested|fermented|aged|matured|blended|grown|pressed)\\b|" +
            "\\b(stages?|steps|process(es)?|manufactur(e|ed|ing|er))\\b|" +
            "\\b(vinification|vinified|winemaking|fermentation|ferment|fermented|malolactic|maturation|matured|barrels?|oak|lees|" +
            "harvest|harvested|pressing|pressed|maceration|destemming|production|blending|blended)\\b|\\bwine\\s+making\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksHowMade(query: String): Boolean = PATTERN.containsMatchIn(query)
}

/** Spots questions about the wines a winery makes ("wines from Château Ausone", "what other wines do they make?"). */
object WineryWinesIntent {
    /** Wording that asks about a winery's wines: plural wines or bottles, "make/produce", "other wines". */
    private val WINES = Regex(
        "\\b(wines|bottles|labels|cuv[ée]es|vintages|range|lineup|portfolio)\\b|\\b(make|makes|produce|produces|offer|offers)\\b|\\bother\\s+wine\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Points back at a winery already in the conversation: "their other wines", "this winery's bottles". */
    private val BACK_REFERENCE = Regex("\\b(their|its|they|this\\s+winery|that\\s+winery|same\\s+winery|other|else|more)\\b", RegexOption.IGNORE_CASE)
    /** Asks for wines of a winery without naming one: "wines from a winery", "other wines from this winery". */
    private val UNNAMED = Regex(
        "\\bwines?\\s+(from|by|of)\\s+(a|the|this|that|my|one)\\s+winery\\b|\\b(this|that|my|their)\\s+winery'?s?\\s+(other\\s+)?(wines|bottles)\\b|\\b(other|more)\\s+wines\\s+(from|by)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val MORE = Regex("\\b(more|another|next|other|else)\\b", RegexOption.IGNORE_CASE)

    fun asksAboutWines(query: String): Boolean = WINES.containsMatchIn(query)
    fun refersBack(query: String): Boolean = BACK_REFERENCE.containsMatchIn(query)
    fun asksForUnnamedWinery(query: String): Boolean = UNNAMED.containsMatchIn(query)
    fun asksForMore(query: String): Boolean = MORE.containsMatchIn(query)

    /** A first answer lists five wines. */
    const val DEFAULT_WINES = 5
}

/** Spots questions about wineries (where one is, or which wineries are somewhere). */
object WineryIntent {
    private val PATTERN = Regex(
        "\\b(wineries|winery|producers?|vineyards?|estates?|bodegas?|vignerons?|domaines?)\\b|" +
            "\\bwho\\s+(makes|produces|grows)\\b|\\b(located|based)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksAboutWineries(query: String): Boolean = PATTERN.containsMatchIn(query)

    /** A question about where one particular winery is. */
    val WHERE_ONE = Regex("\\b(located|based)\\b|\\bwhere\\s+(is|are)\\b", RegexOption.IGNORE_CASE)

    /** The most wineries one answer lists. */
    const val MAX_WINERIES = 10
    /** A first answer gives a few options; the user can ask for more, or for a number. */
    private const val DEFAULT_WINERIES = 3

    private val WORD_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )
    private val COUNT = Regex(
        "\\b(\\d{1,3}|${WORD_NUMBERS.keys.joinToString("|")})\\s+(?:\\w+\\s+)?(wineries|winery|producers?|vineyards?|estates?|bodegas?|vignerons?|domaines?)\\b|" +
            "\\b(?:list|top|names?)\\s+(?:of\\s+)?(\\d{1,3}|${WORD_NUMBERS.keys.joinToString("|")})\\b",
        RegexOption.IGNORE_CASE,
    )

    private val LIST_REQUEST = Regex("\\b(list|name\\s+some|show\\s+me|give\\s+me)\\b", RegexOption.IGNORE_CASE)

    /** True when the user asks for a list of wineries ("5 wineries", "a list of wineries"), as opposed to asking where one is. */
    fun asksForList(query: String): Boolean =
        asksAboutWineries(query) && (COUNT.containsMatchIn(query) || LIST_REQUEST.containsMatchIn(query))

    private const val NOUN = "wineries|winery|producers?|vineyards?|estates?|bodegas?|vignerons?|domaines?"
    private val NUMBER = "\\d{1,3}|${WORD_NUMBERS.keys.joinToString("|")}"
    private val MORE = Regex(
        "\\b(more|another|next|additional|other)\\s+(?:($NUMBER)\\s+)?($NOUN)\\b|\\b($NUMBER)\\s+(more|other|additional|different)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when the user wants further wineries after a list ("10 more", "more wineries", "any other wineries?"), as
     * opposed to "tell me more about this winery". A bare "more" counts only in a very short message.
     */
    fun asksForMore(query: String): Boolean =
        MORE.containsMatchIn(query) || (query.trim().split(Regex("\\s+")).size <= 4 && Regex("\\bmore\\b", RegexOption.IGNORE_CASE).containsMatchIn(query))

    /** How many wineries the user asked for ("10 wineries", "a list of ten"), at most [MAX_WINERIES]; three if no number is given. */
    fun requestedCount(query: String, default: Int = DEFAULT_WINERIES): Int {
        val raw = COUNT.find(query)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[3] } }
            ?: MORE.find(query)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[4] } }
            ?: return default
        val n = raw.lowercase().let { it.toIntOrNull() ?: WORD_NUMBERS[it] } ?: return default
        return n.coerceIn(1, MAX_WINERIES)
    }
}

/**
 * Extra context for a question, added in front of it only when it needs it. Used by the Ask screen (where the open wine
 * supplies a default grape and country) and by Chat's open conversation (where there is no open wine, so the grape and
 * country come from the question itself). Adds: the broader review sample when the user asks what people say; notes for
 * a grape in Grape_Profile_Internal; what wine enthusiasts say (Grape_Profile_Kaggle_Extracted) for a grape without an
 * internal profile or for another country; the other countries to offer; and Wineries_Directory entries. Each block is
 * added once, since the conversation remembers it, and again after a rebuild.
 */
class WineAskExtras(
    private val knowledge: GrapeProfileInternal,
    /** Grapes already covered by the first message; not sent again. */
    private val ownGrapes: Set<String> = emptySet(),
    /** The open wine's grape (all its database spellings) and country, for questions that name no grape or no country. */
    private val ownVariety: List<String> = emptyList(),
    private val ownCountry: String = "",
    /** The open wine's region, used for the production notes when the question names no place. */
    private val ownRegion: String = "",
    /** The French wine production notes, loaded when first needed; null if unavailable. */
    private val loadProduction: (suspend () -> WineProduction?)? = null,
    /** Region style rows for a grape's spellings in a country; empty when the app has none. */
    private val loadKaggleExtracted: suspend (spellings: List<String>, country: String) -> List<KaggleExtractedProfile> = { _, _ -> emptyList() },
    /** The Wineries_Directory, loaded when first needed; null if unavailable. */
    private val loadWineries: (suspend () -> WineriesDirectory?)? = null,
    /** Other countries with the most reviews of a grape (all spellings), leaving out [country] when it is not blank. */
    private val loadOtherCountries: suspend (spellings: List<String>, country: String) -> List<CountryReviewCount> = { _, _ -> emptyList() },
    /** A small unranked sample of wineries with reviews of a grape, optionally in one country. */
    private val loadGrapeWineries: suspend (spellings: List<String>, country: String?, limit: Int, exclude: Set<String>) -> GrapeWineriesSample? = { _, _, _, _ -> null },
    /** The open wine's name, so "what do people say about it?" has a wine to look up; blank when unknown. */
    private val ownWineName: String = "",
    /** Reviews of the wines whose name contains the given text, for a grape's spellings. */
    private val loadWineReviews: suspend (spellings: List<String>, wineName: String) -> List<WineryWine> = { _, _ -> emptyList() },
    /** The winery of the open wine, so "its other wines" has a winery to look up; blank when the wine has none. */
    private val ownWinery: String = "",
    /** Every winery as the reviews spell it, loaded when first needed; null if unavailable. */
    private val loadReviewWineries: (suspend () -> WineriesDirectory?)? = null,
    /** A small unranked sample of the wines the reviews hold for one winery. */
    private val loadWineryWines: suspend (winery: String, country: String?, limit: Int, exclude: Set<String>) -> WineryWinesSample? = { _, _, _, _ -> null },
    private val loadReviews: suspend (spellings: List<String>, country: String) -> VarietyCountryDigest?,
) : AskExtras {
    private val sentGrapes = ownGrapes.toMutableSet()
    private val sentKaggleExtracted = mutableSetOf<String>()
    private val sentWineries = mutableSetOf<String>()
    private val sentReviews = mutableSetOf<String>()
    private val sentAskPlace = mutableSetOf<String>()
    private val sentProduction = mutableSetOf<String>()
    // The grape last named in the conversation, so "How is it made in Bordeaux?" can still find which grape "it" is.
    private var lastGrape: String? = null
    private var lastCountry: String? = null
    // The winery the conversation is about (named, or the open wine's), and the wines already sent for it.
    private var lastWinery: String? = null
    /** The words of a question that are neither the grape, a country nor everyday question wording: a wine name, if any. */
    private fun wineNameIn(query: String, grapes: List<com.sheldondesousa.uncork.data.reviews.GrapeVariety>, countries: List<String>): String? {
        val skip = buildSet {
            grapes.forEach { g -> (g.name.split(" / ") + g.databaseNames).forEach { addAll(GrapeProfileInternal.normalize(it).split(' ')) } }
            countries.forEach { addAll(GrapeProfileInternal.normalize(it).split(' ')) }
            addAll(REVIEW_WORDS)
        }
        val words = GrapeProfileInternal.normalize(query).split(' ')
            .filter { it.length >= 3 && it !in skip && CountryMentions.find(it).isEmpty() }
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private val QUOTED = Regex("[\"“”]([^\"“”]{3,80})[\"“”]")
    private val sentWines = mutableMapOf<String, MutableSet<String>>()

    /** What a list of wineries is about: a grape (with reviews of it) in a country, or a place in the directory. */
    private class WineryTopic(val grape: Pair<String, List<String>>?, val country: String?, val place: Pair<String, String>?) {
        val key = if (grape != null) "g|${grape.first}|${country.orEmpty()}".lowercase() else "d|${place?.first}|${place?.second}".lowercase()
    }
    // Winery names already sent for each topic, so "more" gives different ones, and the topic "more" refers to.
    private val sentWineryNames = mutableMapOf<String, MutableSet<String>>()
    private var lastWineryTopic: WineryTopic? = null

    /** Names to skip: those already sent when the user asks for more, nothing (start again) when they re-ask. */
    private fun skipFor(topic: WineryTopic, more: Boolean): Set<String> =
        if (more) sentWineryNames[topic.key].orEmpty().toSet() else emptySet<String>().also { sentWineryNames.remove(topic.key) }

    private fun remember(topic: WineryTopic, names: List<String>) {
        sentWineryNames.getOrPut(topic.key) { mutableSetOf() } += names.map { it.lowercase() }
        lastWineryTopic = topic
    }
    private val ownGroupNames: Set<String> =
        if (ownVariety.isEmpty()) emptySet() else GrapeVarietyLookup.findMentioned(ownVariety.joinToString(" ")).map { it.name.lowercase() }.toSet()
    // The open wine's own grape already has its other-countries line in the first message.
    private val offeredCountries = ownGroupNames.toMutableSet()

    // Words about the open bottle (its name, winery, grape, place): not new names when the user mentions them.
    private val ownWords: Set<String> =
        (listOf(ownWineName, ownWinery, ownCountry, ownRegion) + ownVariety).flatMap { GrapeProfileInternal.normalize(it).split(' ') }
            .filter { it.isNotBlank() }.toSet()

    override suspend fun lookup(query: String): AskLookup {
        val text = forQuestion(query)
        val blocks = text?.split("\n\n")?.filter { it.isNotBlank() }.orEmpty()
        // Everything that was looked up came back empty: the answer is the fixed refusal, and Gemma is not called.
        if (blocks.isNotEmpty() && blocks.all { it.startsWith(WineFactsNote.NO_NOTES_MARK) }) return AskLookup(null, ChatFlowText.NO_INFORMATION)
        if (blocks.isEmpty() && RefusalGate.shouldRefuse(query, ownWords)) return AskLookup(null, ChatFlowText.NO_INFORMATION)
        return AskLookup(text)
    }

    override suspend fun forQuestion(query: String): String? {
        val blocks = mutableListOf<String>()
        // "Tell me more" or "describe it", with no grape named, is about the grape last discussed (else the open wine's).
        val grapeText = if (GrapeIntent.asksToDescribe(query) && GrapeVarietyLookup.findMentioned(query).isEmpty() && knowledge.findMentioned(query).isEmpty()) {
            (lastGrape ?: ownVariety.firstOrNull())?.let { "$query $it" } ?: query
        } else query
        val mentioned = GrapeVarietyLookup.findMentioned(grapeText)
        val countriesNamed = CountryMentions.find(query)

        // What people say: a grape plus a wine name (the open wine, or one in quotes) finds that wine's reviews; a grape
        // plus a country gives the broader sample. With neither a wine name nor a country, Gemma asks.
        if (ReviewIntent.asksForReviews(query)) {
            val focus = mentioned.firstOrNull()
            val spellings = focus?.databaseNames ?: ownVariety
            val grapeName = focus?.name ?: ownVariety.firstOrNull().orEmpty()
            val country = countriesNamed.firstOrNull() ?: lastCountry ?: ownCountry
            if (spellings.isEmpty()) {
                blocks += "WHAT WINE ENTHUSIASTS SAY\nI do not know which grape the user means. Ask which grape, and the name of the wine, before summarising reviews."
            } else {
                // The wine name is what is left of the question once the grape, country and everyday words are taken out
                // (or text in quotes); failing that, the open wine's name.
                val typed = QUOTED.find(query)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: wineNameIn(query, mentioned, countriesNamed)
                val key = "${grapeName.lowercase()}|${(typed ?: ownWineName).lowercase()}|${country.lowercase()}"
                suspend fun wineBlock(name: String): String? {
                    val found = runCatching { loadWineReviews(spellings, name) }.getOrNull().orEmpty()
                    return if (found.isNotEmpty()) WineFactsNote.wineReviewsBlock(found) else null
                }
                suspend fun sampleBlock(): String = runCatching { loadReviews(spellings, country) }.getOrNull()
                    ?.takeIf { !it.isEmpty }?.let { WineFactsNote.reviewsBlock(it) } ?: WineFactsNote.noNotes("reviews of $grapeName from $country")
                if (sentReviews.add(key)) {
                    val block = when {
                        typed != null -> wineBlock(typed) ?: if (country.isNotBlank() && countriesNamed.isNotEmpty()) sampleBlock()
                            else ownWineName.takeIf { it.isNotBlank() && !it.equals(typed, true) }?.let { wineBlock(it) } ?: WineFactsNote.noNotes("reviews of $typed")
                        countriesNamed.isNotEmpty() -> sampleBlock()
                        ownWineName.isNotBlank() -> wineBlock(ownWineName) ?: WineFactsNote.noNotes("reviews of $ownWineName")
                        country.isNotBlank() -> sampleBlock()
                        else -> {
                            sentReviews.remove(key)
                            "WHAT WINE ENTHUSIASTS SAY\nTo summarise reviews I need the name of the wine, or a country. Ask the user which wine, or which country, they mean."
                        }
                    }
                    blocks += block
                }
            }
        }

        // The country a grape's enthusiast summary is looked up for: the one the user names, else one named earlier in the
        // conversation, else the open wine's.
        val countryNamed = countriesNamed.filterNot { it.equals(ownCountry, ignoreCase = true) }.firstOrNull()
        countriesNamed.firstOrNull()?.let { lastCountry = it }
        val country = countryNamed ?: countriesNamed.firstOrNull() ?: lastCountry ?: ownCountry

        // 1. A grape in Grape_Profile_Internal needs nothing more: its notes are sent once.
        val inNotes = knowledge.findMentioned(grapeText)
        inNotes.firstOrNull()?.let { lastGrape = it.grape }
        val newNotes = inNotes.filter { it.grape !in sentGrapes }.take(MAX_GRAPES)
        if (newNotes.isNotEmpty()) {
            blocks += WineFactsNote.grapeBlock(newNotes)
            sentGrapes += newNotes.map { it.grape }
        }

        // 2. A grape that is not in Grape_Profile_Internal needs a country: what enthusiasts say there. Without one,
        // Gemma asks before anything is looked up.
        val inNotesNames = inNotes.map { it.grape }.toSet()
        val namedGrapes = mentioned
            .filter { grape -> grape.databaseNames.none { name -> knowledge.find(name).grapes.any { it.grape in inNotesNames || it.grape in ownGrapes } } }
            .filter { grape -> knowledge.find(grape.name.substringBefore(" / ")).grapes.isEmpty() }
        val regionTargets = buildList {
            namedGrapes.forEach { add(it.databaseNames to it.name) }
            // 3. Another country named for the open wine's grape: that country's style, attributed to enthusiasts.
            if (countryNamed != null && namedGrapes.isEmpty() && mentioned.isEmpty() && ownVariety.isNotEmpty()) add(ownVariety to ownVariety.first())
        }
        if (regionTargets.isNotEmpty() && country.isBlank()) {
            val grape = regionTargets.first().second
            if (sentAskPlace.add(grape.lowercase())) {
                blocks += "WHERE\nThe app has no notes for $grape, and the user has not said which country or region they mean. Before saying anything about it, ask which country or region."
            }
        } else if (country.isNotBlank()) {
            regionTargets.take(MAX_GRAPES).forEach { (spellings, grape) ->
                val key = "${grape.lowercase()}|${country.lowercase()}"
                if (key in sentKaggleExtracted) return@forEach
                sentKaggleExtracted += key
                val styles = runCatching { loadKaggleExtracted(spellings, country) }.getOrNull().orEmpty()
                blocks += if (styles.isNotEmpty()) WineFactsNote.kaggleExtractedBlock(styles, grape)
                else WineFactsNote.noNotes("$grape in $country")
            }
        }

        // Offer other countries once for each grape the user names.
        mentioned.take(MAX_GRAPES).forEach { grape ->
            if (!offeredCountries.add(grape.name.lowercase())) return@forEach
            val others = runCatching { loadOtherCountries(grape.databaseNames, country) }.getOrNull().orEmpty()
            if (others.isNotEmpty()) blocks += WineFactsNote.otherCountriesLine(others)
        }

        // 3b. "How is Merlot made in Bordeaux?": the French production notes for that grape and place.
        productionBlock(query, countriesNamed)?.let { blocks += it }

        // Wines of a winery, asked for without naming one when none is in mind: Gemma asks which winery.
        if (WineryWinesIntent.asksForUnnamedWinery(query) && resolveWinery(query, countriesNamed.firstOrNull()) == null) {
            blocks += "WINERY\nThe user asks about the wines of a winery but no winery is in mind. Ask which winery they mean before naming any wines."
        }
        var grapeWineriesAdded = false
        // 4a. "Wineries for Merlot": wineries that have reviews of the grape named in the question.
        if (WineryIntent.asksAboutWineries(query)) {
            // "5 wineries" with no grape named means the open wine's grape.
            val focus = mentioned.firstOrNull()?.let { it.name to it.databaseNames }
                ?: ownVariety.firstOrNull()?.let { it to ownVariety }
            if (focus != null) {
                val wineryCountry = countriesNamed.firstOrNull() ?: ownCountry.takeIf { it.isNotBlank() }
                // Sent every time the user asks (the same unranked sample), so a repeated or reworded ask still has the list.
                val topic = WineryTopic(focus, wineryCountry, null)
                val sample = runCatching {
                    loadGrapeWineries(focus.second, wineryCountry, WineryIntent.requestedCount(query), skipFor(topic, false))
                }.getOrNull()
                if (sample != null && sample.wineries.isNotEmpty()) {
                    remember(topic, sample.wineries.map { it.winery })
                    blocks += WineFactsNote.grapeWineriesBlock(focus.first, wineryCountry, sample)
                    grapeWineriesAdded = true
                }
            }
        }

        // 4. Wineries: a named winery's location, or a small sample of the wineries in a place the user names.
        val wineryLoader = loadWineries
        // A winery named in the question is looked up even without a word like "winery" ("Tell me about Château Ausone");
        // a sample of the wineries in a place needs the question to ask about wineries.
        if (wineryLoader != null) {
            val asksWineries = WineryIntent.asksAboutWineries(query)
            val directory = runCatching { wineryLoader() }.getOrNull()
            if (directory != null) {
                // A country the user names is strict. The open wine's country is only a preference: a winery in
                // another country is still found if it is not listed in this one.
                val own = ownCountry.takeIf { it.isNotBlank() }
                val mentioned = countryNamed?.let { directory.findMentioned(query, it) }
                    ?: directory.findMentioned(query, own).ifEmpty { directory.findMentioned(query, null) }
                val named = mentioned.filter { "${it.winery}|${it.country}|${it.region}".lowercase() !in sentWineries }
                // A winery already sent is not re-sent, and must not turn into a sample of the open wine's country.
                if (mentioned.isNotEmpty() && named.isEmpty()) return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                if (named.isNotEmpty()) {
                    sentWineries += named.map { "${it.winery}|${it.country}|${it.region}".lowercase() }
                    blocks += WineFactsNote.wineriesBlock(named, "entries for wineries named in the question")
                } else if (asksWineries) {
                    val region = directory.regionMentioned(query, countryNamed ?: ownCountry.takeIf { it.isNotBlank() })
                    val place = region ?: (countryNamed ?: countriesNamed.firstOrNull() ?: lastCountry ?: own)?.let { it to "" }
                    // A list of wineries needs a country, region or sub-region: with none, Gemma asks for one.
                    // "Where is X located?" about a winery that is not listed is answered as not listed, not by asking for a place.
                    if (place == null && !grapeWineriesAdded && !WineryIntent.WHERE_ONE.containsMatchIn(query)) {
                        blocks += "WHERE\nThe user asks about wineries but has not said where. Before naming any winery, ask which country, region or sub-region they mean."
                    }
                    // Sent every time the user asks, so a repeated or reworded ask still has the list.
                    if (place != null) {
                        val topic = WineryTopic(null, null, place)
                        val sample = directory.sampleIn(place.first, place.second.takeIf { it.isNotBlank() }, WineryIntent.requestedCount(query), skipFor(topic, false))
                        if (sample.isEmpty()) blocks += WineFactsNote.noNotes("wineries in ${directory.placeLabel(place.first, place.second.takeIf { it.isNotBlank() })}")
                        if (sample.isNotEmpty()) {
                            remember(topic, sample.map { it.winery })
                            val where = directory.placeLabel(place.first, place.second.takeIf { it.isNotBlank() })
                            // A broad place ("Sonoma") covers several listed regions: say so, so each can be offered as an option.
                            val variants = directory.regionVariants(place.first, place.second)
                            val covers = if (variants.size > 1) " (covering the listed regions ${variants.joinToString(", ")}; offer them as options)" else ""
                            blocks += WineFactsNote.wineriesBlock(
                                sample,
                                "a small, unranked sample of the ${directory.countOf(place.first, place.second.takeIf { it.isNotBlank() })} wineries listed in $where$covers; not a complete list and not the best wineries",
                            )
                        }
                    }
                }
            }
        }
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun productionAsk(missing: String) =
        "WINE PRODUCTION\nThe user asks how a wine is made, but I do not know $missing. Ask for it before explaining."

    private suspend fun productionBlock(query: String, countriesNamed: List<String>): String? {
        if (!ProductionIntent.asksHowMade(query)) return null
        // A grape and a place (country, region or sub-region) are both needed. Whichever is missing, Gemma asks for.
        val grapeKnown = GrapeVarietyLookup.findMentioned(query).isNotEmpty() || knowledge.findMentioned(query).isNotEmpty() ||
            ownVariety.isNotEmpty() || lastGrape != null
        val placeKnown = countriesNamed.isNotEmpty() || lastCountry != null || ownCountry.isNotBlank() || ownRegion.isNotBlank()
        val loaded = loadProduction?.let { runCatching { it() }.getOrNull() }
        val padded = " ${GrapeProfileInternal.normalize(query)} "
        val placeInQuestion = loaded?.let { p -> p.grapes.any { p.placeMentioned(it, query) != null } } == true
        if (!grapeKnown && !(placeKnown || placeInQuestion)) return productionAsk("which grape, or which country, region or sub-region")
        if (!grapeKnown) return productionAsk("which grape")
        if (!placeKnown && !placeInQuestion) return productionAsk("which country, region or sub-region")
        val production = loaded ?: return null
        val about = GrapeVarietyLookup.findMentioned(query).firstOrNull()?.name ?: knowledge.findMentioned(query).firstOrNull()?.grape
            ?: ownVariety.firstOrNull() ?: lastGrape ?: "this grape"
        val noInfo = WineFactsNote.noNotes("how $about is made there")
        // The notes are about France only: a question naming another country, or an open wine from one, is not theirs.
        val frenchNamed = countriesNamed.any { it.equals(production.country, ignoreCase = true) }
        if (countriesNamed.isNotEmpty() && !frenchNamed) return noInfo
        // The grape named in the question, else the open wine's.
        val named = knowledge.findMentioned(query).map { it.grape }
        val candidates = when {
            named.isNotEmpty() -> named
            ownVariety.isNotEmpty() -> ownVariety.take(1).flatMap { knowledge.find(it).grapes.map { g -> g.grape } }
            else -> listOfNotNull(lastGrape)
        }
        val grape = production.grapes.firstOrNull { pg ->
            " ${GrapeProfileInternal.normalize(pg.name)} " in padded ||
                knowledge.find(pg.name).grapes.firstOrNull()?.grape?.let { it in candidates } == true
        } ?: return noInfo
        // Only the seeded pairs: Merlot in Bordeaux and Chardonnay in Burgundy (or a place inside them).
        val region = PRODUCTION_SCOPE[grape.name] ?: return noInfo
        if (ownCountry.isNotBlank() && !frenchNamed && !ownCountry.equals(production.country, ignoreCase = true)) return noInfo
        val at = production.placeMentioned(grape, query)
            ?: ownRegion.takeIf { it.isNotBlank() && production.pathTo(grape, it) != null }
            // The notes cover this grape, but the place is only a country: Gemma asks for a region or sub-region of it.
            ?: return "WINE PRODUCTION\nThe user asks how ${grape.name} is made, but only the country is known. Ask which region or sub-region of $region they mean before explaining."
        if (production.pathTo(grape, at)?.contains(region) != true) return noInfo
        if (!sentProduction.add("${grape.name}|$at".lowercase())) return null
        return WineFactsNote.productionBlock(grape.name, at, production.country, production.resolve(grape, at))
    }

    /**
     * The winery a question is about: one it names (matched to the reviews' own spelling), else, when it points back
     * ("their other wines", "this winery's bottles"), the winery in the conversation or the open wine's.
     */
    private suspend fun resolveWinery(query: String, country: String?): String? {
        val index = loadReviewWineries?.let { runCatching { it() }.getOrNull() }
        val named = index?.findMentioned(query, country, limit = 1)?.firstOrNull()
            ?: index?.findMentioned(query, null, limit = 1)?.firstOrNull()
        if (named != null) return named.winery
        if (!WineryWinesIntent.refersBack(query) && !WineryWinesIntent.asksForUnnamedWinery(query)) return null
        return lastWinery ?: ownWinery.takeIf { it.isNotBlank() }
    }

    /** "Wines from this winery": up to five of the wines the reviews hold for it, with their details. */
    private suspend fun wineryWinesAnswer(query: String): String? {
        val asks = WineryWinesIntent.asksAboutWines(query)
        val more = lastWinery != null && WineryWinesIntent.asksForMore(query) && asks
        if (!asks && !more) return null
        val winery = resolveWinery(query, CountryMentions.find(query).firstOrNull()) ?: return null
        val key = winery.lowercase()
        val sameAsLast = lastWinery?.equals(winery, ignoreCase = true) == true
        val skip = if (more && sameAsLast) sentWines[key].orEmpty().toSet() else emptySet<String>().also { sentWines.remove(key) }
        val sample = runCatching {
            loadWineryWines(winery, null, WineryIntent.requestedCount(query, WineryWinesIntent.DEFAULT_WINES), skip)
        }.getOrNull()
        lastWinery = winery
        if (sample == null || sample.wines.isEmpty()) {
            return if (skip.isNotEmpty()) "That is every wine I have listed for $winery. Ask about another winery and I can list its wines."
            else ChatFlowText.NO_INFORMATION
        }
        sentWines.getOrPut(key) { mutableSetOf() } += sample.wines.map { it.name.lowercase() }
        return WineFactsNote.wineryWinesList(sample, more && sameAsLast)
    }

    override suspend fun wineryList(query: String): String? {
        wineryWinesAnswer(query)?.let { return it }
        val more = lastWineryTopic != null && WineryIntent.asksForMore(query)
        if (!more && !WineryIntent.asksForList(query)) return null
        val count = WineryIntent.requestedCount(query)
        val countriesNamed = CountryMentions.find(query)
        val grapeNamed = GrapeVarietyLookup.findMentioned(query).firstOrNull()
        val directory = loadWineries?.let { runCatching { it() }.getOrNull() }
        countriesNamed.firstOrNull()?.let { lastCountry = it }
        val wineryCountry = countriesNamed.firstOrNull() ?: lastCountry ?: ownCountry.takeIf { it.isNotBlank() }
        val region = directory?.regionMentioned(query, wineryCountry)
        // "10 more" names nothing new, so it continues the last list. Anything named starts (or continues) that topic.
        val topic = if (more && grapeNamed == null && countriesNamed.isEmpty() && region == null) lastWineryTopic!! else {
            // A grape (named, or the open wine's) gives wineries with reviews of it, otherwise the place named.
            val focus = grapeNamed?.let { it.name to it.databaseNames } ?: ownVariety.firstOrNull()?.let { it to ownVariety }
            when {
                focus != null -> WineryTopic(focus, wineryCountry, null)
                else -> WineryTopic(null, null, region ?: wineryCountry?.let { it to "" } ?: return null)
            }
        }
        val skip = skipFor(topic, more)
        if (topic.grape != null) {
            val sample = runCatching { loadGrapeWineries(topic.grape.second, topic.country, count, skip) }.getOrNull()
            if (sample != null && sample.wineries.isNotEmpty()) {
                remember(topic, sample.wineries.map { it.winery })
                return WineFactsNote.grapeWineriesList(topic.grape.first, topic.country, sample, more)
            }
            if (more) return noMoreWineries(topic)
            // No reviews for that grape: fall back to the open wine's country in the directory.
            val fallback = wineryCountry?.let { it to "" } ?: return null
            return directoryList(directory ?: return null, WineryTopic(null, null, fallback), count, false)
        }
        return directoryList(directory ?: return null, topic, count, more)
    }

    private fun directoryList(directory: WineriesDirectory, topic: WineryTopic, count: Int, more: Boolean): String? {
        val place = topic.place ?: return null
        val sample = directory.sampleIn(place.first, place.second.takeIf { it.isNotBlank() }, count, skipFor(topic, more))
        if (sample.isEmpty()) return if (more) noMoreWineries(topic) else null
        remember(topic, sample.map { it.winery })
        return WineFactsNote.directoryWineriesList(
            sample, directory.placeLabel(place.first, place.second.takeIf { it.isNotBlank() }),
            directory.countOf(place.first, place.second.takeIf { it.isNotBlank() }), more,
        )
    }

    private fun noMoreWineries(topic: WineryTopic): String {
        val what = topic.grape?.let { "with reviews of ${it.first}${topic.country?.let { c -> " in $c" }.orEmpty()}" }
            ?: "in ${listOfNotNull(topic.place?.second?.takeIf { it.isNotBlank() }, topic.place?.first).joinToString(", ")}"
        return "That is every winery I have listed $what. Ask about another grape, region or country and I can list wineries there."
    }

    override fun reset() {
        sentGrapes.clear()
        sentGrapes += ownGrapes
        sentKaggleExtracted.clear()
        sentWineries.clear()
        sentReviews.clear()
        sentProduction.clear()
        lastGrape = null
        sentWineryNames.clear()
        lastWineryTopic = null
        offeredCountries.clear()
        offeredCountries += ownGroupNames
    }

    private companion object {
        const val MAX_GRAPES = 2

        /** Everyday question and review wording, never part of a wine's name. */
        val REVIEW_WORDS = setOf(
            "what", "whats", "how", "who", "which", "does", "did", "have", "has", "are", "was", "were", "the", "and", "for", "from", "about",
            "with", "that", "this", "tell", "say", "says", "said", "saying", "think", "thinks", "thought", "feel", "people", "folks", "others",
            "everyone", "everybody", "they", "their", "them", "you", "your", "can", "could", "would", "should", "please", "wine", "wines",
            "review", "reviews", "reviewed", "reviewer", "reviewers", "critic", "critics", "opinion", "opinions", "feedback", "enthusiast",
            "enthusiasts", "expert", "experts", "consensus", "like", "good", "any", "some", "more", "much", "very", "its", "give", "show",
            "other", "another", "others", "ones", "most", "many", "all", "every", "anyone", "anybody", "else", "been", "being", "ever", "into", "each", "when", "where", "why", "lovers", "drinkers", "also", "there", "than", "then", "yes", "not", "but", "just", "bottle", "winery", "taste", "tastes",
        )

        /** The only grape and region the French production notes may be used for. */
        val PRODUCTION_SCOPE = mapOf("Merlot" to "Bordeaux", "Chardonnay" to "Burgundy")
    }
}
