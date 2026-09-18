# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Last updated:** 17 September 2026
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Gemma leads a fixed three-question onboarding sequence covering wine type, country, and other attributes; explicit lack-of-preference answers are valid and ambiguous answers are clarified at the current step. Kotlin renders Gemma's visible output, retains lightweight field coverage, and orchestrates the Kaggle, cache, and web data sources. When all three questions close, Gemma emits one full preference snapshot and structured recommendation context, and the app queries Kaggle immediately. Kaggle results are followed by an offer to run a broader web search. A Kaggle miss follows AC10e and AC10f. Newly resolved web-search profile data is written to the growing on-device `VarietyRegionProfile` Room table with source `web_search`.

Find is the default entry point, accepting any combination of Type, Location, Sweetness, Tannin, Body, and Acidity selections. It queries on-device Gemma and the bundled database independently, with no Chat fallback, profile-cache access, web search, or automatic History recording. See Section 6A.

## 2. MVP Scope

Five screens:
1. Splash Screen
2. Find
3. Main Conversation Screen
4. Profile Page
5. My List

## 3. Out of Scope (this MVP)

- Suggestions / discovery feed (a separate curated/"surprise me" feed)
- Wine bottle imagery (typography carries the visual weight instead)
- Cloud sync / multi-device support
- Location and price lookup (Google Places) — discussed earlier in the project, not included in these five screens. **Flagging this explicitly since it was part of earlier scoping — confirm this is an intentional deferral, not an oversight.**
- Analytics backend beyond local, on-device logging

## 4. Shared Data Schema

Used by AI-sourced profiles and Kaggle reviewer matches so the results remain comparable without prose interpretation:

```
name
winery
variety
country       (nullable string)
province
body          (string: single value | range | optional trailing * | Unknown)
tannin        (string: single value | range | optional trailing * | Unknown)
acidity       (string: single value | range | optional trailing * | Unknown)
sweetness     (optional string: Bone-Dry | Off-Dry | Sweet | Unknown; generated for Find profiles)
flavor_notes  (2-4 short tags)
summary        (nullable string, Gemma only, fewer than 200 characters)
review_summary (nullable string, full untruncated Kaggle review text)
web_summary   (nullable string, 1-2 sentences)
```

Critic `rating` is a nullable integer reserved for the Kaggle `points` field. It is not requested from or populated by Gemma.

`review_summary` is populated only when the app matches a real Kaggle review. It retains the full, untruncated review text with no sentence or character cap. Gemma and the web-search fallback never populate it. `web_summary` remains limited to a short AI-synthesized paraphrase.

`web_summary` is populated only for an option resolved through the existing web-search fallback after Kaggle misses or fails under AC10e or AC10f. It is an AI-synthesized paraphrase of the search findings, never verbatim source-page text. Gemma suggestions and Kaggle matches never populate it. This does not add a new user action or allow web search to run when Kaggle has returned matches.

`summary` is populated only for Gemma recommendations. It contains a few concise descriptive lines about the recommendation and stays below 200 characters. Kaggle and web-search options never populate it.

The Profile Page renders the shared factual fields for every option, followed by exactly one source-specific narrative field: `Summary` for Gemma, `Critic Review` for Kaggle, or `Web Summary` for a cached or live web-search option. The other two narrative fields are omitted rather than displayed together or shown as `Unknown`. Unrecognized or unresolved shared fields render as `Unknown` rather than being guessed. `Unknown` is styled in muted or secondary text, visually distinct from resolved values.

`country` is a distinct schema field immediately before `province`. Gemma, a Kaggle match, or the web-search fallback may resolve it through the same flow used for variety and province.

### Attribute value shapes

`body`, `tannin`, and `acidity` are plain strings rather than fixed enums. Each can use one of these shapes, regardless of whether its source is `kaggle_derived` or `web_search`:

| Shape | Example | Meaning |
|---|---|---|
| Single value | `high` | One category clearly dominates the available evidence. |
| Range | `light to full` | Multiple categories, each representing at least 20% of the group's matched signal, show that the country-province-variety combination spans a genuine range. |
| Single value, thin evidence | `medium*` | Only one category is present, backed by fewer than three Kaggle reviews or by one AI web synthesis. |
| Range, thin evidence | `light to full*` | A range in which at least one contributing category has thin support. |
| Unknown | `Unknown` | No usable signal was found. |

Implementations must preserve these strings as supplied. They must not model the fields as a Kotlin enum, sealed class, or another type restricted to `light`, `medium`, `full`, and `Unknown`.

For category-level requests such as "Malbec," the AI may use learned knowledge to provide typical variety characteristics even when no exact bottle is identified. Bottle-specific winery, vintage, critic rating, or provenance claims remain `Unknown` unless a separate source supplies them. Critic rating must come from a real Kaggle match.

### Country-province-variety profile storage

The app has a growing on-device Room table named `VarietyRegionProfile`, keyed by the combination of `country`, `province`, and `variety`, in that order. Each row stores those key values; nullable body, tannin, and acidity values; a list of flavor notes; generation metadata; a source value of either `kaggle_derived` or `web_search`; and one cached web-search option containing its winery, `web_summary`, and other resolved option fields.

- On first launch, if the table is empty, the app reads `country_province_variety_profiles.json` from packaged assets and inserts all profiles in one Room batch operation.
- Asset parsing and database writes run on an IO dispatcher so startup work does not block the interface.
- Later launches skip asset seeding when the table already contains rows.
- Replace-on-conflict inserts allow a live web-search result to add or refresh one country/province/variety combination.
- The repository supports exact country/province/variety lookup. When no cached option exists, the live chat fallback writes the resolved profile and first usable web option through the Room pipeline under source `web_search`, replacing any previously cached row for that combination. When a cached first-position option already exists, supplementary live results do not replace or update it. Only one option is cached per profile row; caching supplementary options would require a separate linked table and is outside this design.
- The profile table does not provide critic ratings. Those come only from a matched genuine Kaggle `points` value.

### Kaggle duplicate-wine aggregation

Before the Kaggle database is made available to the app, exact duplicate source rows with both identical `name` and identical review text are removed so the same review is never counted or displayed twice. The remaining rows are grouped as the same wine when all five fields match: `name`, `winery`, `country`, `province`, and `variety`.

- A group containing one review passes through unchanged.
- A group containing multiple distinct reviews becomes one database row.
- The merged `points` value is the average of all non-null contributing points, rounded to the nearest whole number. It is `null` when every contributing points value is null.
- The merged `review_summary` contains every distinct contributing review in source order, labeled and separated as `Review 1: … || Review 2: … || Review 3: …`. Reviews are not dropped, shortened, or summarized.
- Ranking under AC6c and AC-KaggleRanking-Nulls uses the merged points value.

The 15 September 2026 database build started with 119,928 cleaned rows, merged 688 wines that had multiple distinct reviews, and produced 119,030 final rows.

### Response-source rules for Chat

These rules apply to Chat. Find uses the independent rules in Section 6A.

- Gemma targets three valid structured recommendations as lookup context. Once Q1-Q3 close, the app queries the enthusiast database immediately rather than displaying Gemma cards first.
- Kaggle cards trigger an optional offer for a broader web search. A Kaggle miss proceeds to the web-search layer automatically. Every later layer excludes cards already shown.
- Kaggle options may supply a real wine name, winery, post-aggregation critic points, and full reviewer text. More than three matches are reduced using `ORDER BY points DESC, winery ASC`, making winery alphabetical order the deterministic tiebreak for tied or null points.
- Either Kaggle search returning no matches or failing technically triggers the cache lookup. A usable cached option stops the chain. When the cache also misses, the app runs the full web search. Newly found web options retain the search engine's result order, and the first option is cached. Each web option receives its own AI-synthesized `web_summary`. Web options never receive critic rating or review summary values.
- A separate winery-verification web search may verify a specific Kaggle winery for the Profile Page badge.
- No citations or source-switch controls are displayed in the response and detail flow.

---

## 5. Splash Screen

**Purpose:** App branding, plus first-launch acquisition and subsequent readiness checks for the on-device model.

### Navigation & Display
- **AC1:** Given app cold start, when the splash screen loads, then app branding (name/logo) is displayed centered on screen.
- **AC2:** Given the model is not installed, when its authenticated Hugging Face download is in progress, then a progress bar displays the percentage calculated from downloaded and expected bytes.
  - **AC2a:** Given the app is checking or verifying the local model, then the bar uses an indeterminate animation rather than showing a misleading percentage.

### Data & Content
- **AC3:** Given the verified model already exists in private app storage, when the app starts, then it navigates directly to the Main Conversation Screen without network access.
- **AC4:** Given the model does not exist, then the user is asked for a Hugging Face read token, which is used for the download and is not persisted.
- **AC4a:** Given the download or integrity verification fails, then the app retries up to three times before showing the designed error state with a manual retry option.
- **AC4b:** Given the country-province-variety Room table is empty when the app starts, then `country_province_variety_profiles.json` is parsed from packaged assets and inserted off the main thread. Given the table already contains rows, seeding is skipped without replacing runtime additions.

### Error Handling
- **AC5:** Given the on-device model load fails, when the user taps retry, then the model load is re-attempted.
  - **AC5a:** Given retry fails three consecutive times, then a message advises checking device storage or compatibility. No cloud fallback applies to this check — it is specifically verifying the on-device model.

**Open assumptions:**
- Maximum acceptable splash duration before showing a "taking longer than usual" message — not yet defined. Suggest 3-5 seconds as a placeholder pending device testing.
- On-device path is settled on the pinned `gemma-4-E2B-it.litertlm` artifact and LiteRT-LM runtime. Download progress is byte-based; local checking and SHA-256 verification are indeterminate phases.

---

## 6. Main Conversation Screen

**Purpose:** Single chat-style interface, casual and personable in tone. Each cycle targets three option cards and requires at least one. Gemma is attempted first, followed by Kaggle and the web-search layer when the preceding source has no valid card. The detailed profile loads on the Profile Page.

### Navigation & Display
- **AC1:** Given the app has passed splash, when the Main Conversation Screen loads, then a persistent text input is displayed at the bottom of the screen.
  - **AC1a:** Given a new conversation opens, then UnCork briefly introduces itself as the user's personal sommelier and immediately asks whether they want **red**, **rosé**, **white**, **sparkling**, **sweet**, or **fortified** wine. The six options are bold so the first critical filter is clear without an open-ended introductory question.
  - **AC1b:** Given the launch greeting is already visible, when Gemma generates any later response, then it does not introduce itself again.
- **AC2:** Given a prior response exists in the current session, when the screen renders, then the conversation thread displays above the input, most recent at the bottom.
  - **AC2a:** Given the app is closed and reopened (a new session), when the Main Conversation Screen loads, then the screen starts fresh — the previous session's suggestions are not shown inline, and only explicitly saved wines remain available in My List.
  - **AC2b:** Given the LLM can produce any number of suggestions within one session, when the user's query shifts to a different topic within the same session, then earlier suggestion cards remain visible in the thread rather than being cleared or collapsed.

### Content Tone
- **AC3:** Given a query contains enough relevant wine information, when Gemma responds successfully, then it returns exactly three distinct lightweight card records in one hidden `[WINE_CARDS]` JSON array. Each record contains `name`, `variety`, `country`, `province`, and a relevant `summary` of fewer than 200 characters. Gemma must choose options for which both name and country are known; neither mandatory field may be `Unknown`. Variety and province may be `Unknown`.
- **AC3a:** Given Gemma closes Q1, Q2, and Q3 and returns its final structured response, then the app uses the accumulated request and resolved card fields to query Kaggle immediately. Gemma's cards provide lookup context but are not displayed before this handoff.
- **AC3b:** Given all fields in the fixed question set are closed (Q1: type, Q2: country, Q3: other attributes), when Gemma generates that turn's final response, then it emits the full preference snapshot once and the app proceeds immediately to the Kaggle search. The app does not wait for a separate enthusiast-search confirmation at this point.
- **AC3c:** Given Gemma is generating its response, then visible prose streams into a temporary assistant message as tokens arrive. Hidden coverage, snapshot, and card data never appears in the thread. Once the final structured response is parsed, Kotlin uses it as Kaggle lookup context and displays the resulting source cards.
- **AC3d:** Given a new conversation begins, the fixed launch greeting introduces UnCork and presents the six primary wine types. Every user reply after that greeting is sent directly to Gemma. Gemma tracks answered questions and leads the remaining onboarding conversation one question at a time: wine type, country, then body, tannin, acidity, or flavor. Onboarding questions and clarification responses produce no visible wine cards and do not trigger Kaggle or web search.
- **AC3e:** Given Gemma has already used a conversational acknowledgement or filler in the current thread, when it handles a later clarification answer, then it asks the next question directly and does not repeat that filler. Phrases such as "That's a good starting point," "Great choice," and "Sounds good" must not recur across consecutive clarification turns.
- **AC3e-1:** Given Gemma or the app asks any question containing selectable answers, then every option is individually formatted in bold Markdown. This is a hard rule covering the launch wine types, the second clarification, repeated clarifications, explanatory follow-ups, and every other question with alternatives. Surrounding prose and punctuation remain unbolded; a question containing an unbolded selectable option is invalid.
- **AC3f:** Given the user supplies `no preference`, `surprise me`, `choose for you`, or an equivalent uncertainty or lack-of-preference answer at an applicable onboarding step, then Gemma treats it as a valid answer and advances without demanding specificity. Directional answers are retained for later fallback searches.
- **AC3g:** Given the user gives a genuinely uninterpretable answer to an onboarding question, then Gemma keeps the current question pending and asks one concise clarification. Kotlin does not classify or rewrite the user's conversational answer.
- **AC3g-1:** Given Gemma asks about wine attributes, when it presents the choice, then it uses terms such as body, tannin, and acidity and offers to explain unfamiliar terms.
- **AC3h:** Gemma owns conversational decisions, including deviations, revised answers, uncertainty, clarification, and the three sequential onboarding questions. Kotlin does not classify or rewrite the user's message; it prepends the current field coverage described in AC3i. Kotlin continues to own structured-output parsing, card rendering, and fallback execution.
- **AC3i:** Kotlin holds session-only field coverage for Q1, Q2, and Q3. Before completion, each Gemma turn emits a hidden `[FIELD_COVERAGE]` block and Kotlin feeds the latest coverage back on the next turn. Kotlin does not request or require the full preference snapshot while any question remains open. Once Q1–Q3 are all closed, Gemma emits one full `[STATE_SNAPSHOT]` containing `type`, `country`, `body`, `tannin`, `acidity`, `variety`, `flavor`, and `occasion`. Coverage and snapshot state are never written to Room and reset with the conversation session.
- **AC3j:** Kotlin removes `[FIELD_COVERAGE]`, `[STATE_SNAPSHOT]`, `[WINE_CARDS]`, `[NEEDS_CLARIFICATION]`, and `[OUT_OF_SCOPE]` control content before rendering Gemma text. Clarification, digression, out-of-scope, and onboarding turns never show cards or trigger a fallback source. A valid all-closed coverage block is the only signal that the question set is complete and that AC3b may run.
- **AC3k:** Given final Gemma cards are parsed, Kotlin compares every resolved snapshot field represented in the card (`type`, `country`, `body`, `tannin`, `acidity`, `variety`, `flavor`, and `occasion`) with the corresponding response field. `Unknown` snapshot values impose no constraint, so unspecified attributes returned by Gemma remain valid. A mismatched card is discarded without replacement and the log names the mismatched field.
- **AC3l:** Given Gemma misses or mangles a hidden marker, Kotlin keeps extraction strict and does not guess state from the near miss. Independently, the display sanitizer removes fenced structured blocks, brace-delimited objects containing multiple key-value pairs, and near-miss snapshot or card labels so raw JSON never appears in the conversation. The prior valid snapshot remains active.
- **AC3m:** Given Gemma asks Q2, Q3, or another question with choices, then bold text identifies real selectable values such as **France**, **Italy**, **light**, **full**, **low**, or **high**. A field label such as country, body, or tannin is never formatted as though it were itself a selectable answer.
- **AC3n:** The former mandatory recap-and-confirmation turn is retired. Closing Q3 completes the fixed question set, produces the one full snapshot, and triggers AC3b immediately. A later correction reopens the affected coverage field and prevents another completed handoff until Gemma closes the revised field.

### Field Coverage Tracking

**Purpose:** Determine when Gemma has gathered enough information to complete the fixed question sequence while allowing wine-related digressions.

- **AC-Cov1:** Given each conversational turn before completion, when Gemma generates its response, then it emits lightweight `clarify` or `closed` coverage for Q1, Q2, and Q3 in `[FIELD_COVERAGE]`. This replaces full snapshot generation until all three questions are closed.
- **AC-Cov2:** Gemma classifies the current answer using question-specific criteria:
  - **Q1 — type:** `closed` when the answer semantically identifies a recognized wine type such as red, white, rosé, sparkling, sweet/dessert, or fortified, including a clear synonym or description. Otherwise it remains `clarify`.
  - **Q2 — country:** `closed` when the answer identifies a real wine-producing country or clearly implies one, such as `France` or `something Italian`. Otherwise it remains `clarify`.
  - **Q3 — other attributes:** `closed` when the answer supplies at least one valid body, tannin, acidity, or flavor descriptor, or explicitly declines or states no preference. Otherwise it remains `clarify`.
  - A `closed` answer advances to the next open question. A `clarify` answer repeats the current question and does not advance.
- **AC-Cov3:** Given a field closes through an explicit decline or no-preference answer, then its eventual snapshot value remains `Unknown`; this does not block completion.
- **AC-Cov4:** Given Q1, Q2, and Q3 are all `closed`, then Gemma's response is the final recommendation turn and AC3b applies.
- **AC-Cov5:** Given one user message supplies answers for multiple questions, then Gemma closes every applicable question on that turn and triggers AC3b immediately when none remain open.
- **AC-Cov6:** Given the user revises a previously closed answer in the same session, then the affected field reopens while Gemma applies the correction. AC3b does not fire again until all coverage fields are closed under the revised understanding.
- **AC-Cov8 — digression:** Given the user asks about wine, the wine market, or the wine industry instead of answering the current question, then Gemma answers briefly from its own expertise and repeats the interrupted question. Coverage does not change.
- **AC-Cov9 — off-domain:** Given the user asks something outside wine, the wine market, or the wine industry, then Gemma states that this falls outside its expertise and that it can help with those wine domains, then repeats the interrupted question. Coverage does not change.
- **AC-Cov10 — attribute discretion:** Given `body`, `tannin`, `acidity`, or `flavor` remains `Unknown` in the final snapshot, then Gemma, Kaggle, and web search may return any value for it. Given one of these attributes is resolved, each subsequent source treats it as a filter. `flavor` is used for display or ranking rather than SQL equality because it is multi-valued. Variety, country, and province remain governed by AC10.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC4a:** Given Gemma is asked for recommendations in Chat, then its hidden `[WINE_CARDS]` output contains `name`, `variety`, `country`, `province`, and a descriptive `summary` of fewer than 200 characters; it does not generate winery, rating, or review information. Given a Gemma card's Profile Page opens, the summary displays immediately while a separate on-device request preserves the card fields and resolves `body`, `tannin`, `acidity`, `flavor_notes`, and `suggested_pairing`. `suggested_pairing` remains `Unknown` unless the original request explicitly asked for a food or cheese pairing. Gemma never produces `winery`, `rating`, `review_summary`, `web_summary`, or `confidence` in the full profile.
- **AC5:** Given cheese is not requested, when a response is generated, then no cheese pairing is included by default.
  - **AC5a:** Given the user explicitly requests a cheese pairing, then a cheese suggestion is appended in the same casual tone.

### Wine options
- **AC6:** Given Gemma, Kaggle, the cache, or the web-search fallback returns one or more matches, when the app displays them, then the permitted number of option cards render inline in the chat thread. Each card shows the wine name; the winery when available; and origin formatted as `Country, Province`, displaying `Unknown` for either unresolved origin field. Critic rating remains available on the Profile Page and is not shown on the option card.
  - **AC6a:** Given the user taps an option card, when tapped, then the app navigates to the Profile Page with that option's full field set. Opening a card does not save it.
  - **AC6b:** Given an option matches a wine already in My List, when it appears in the thread, then it shows the existing personal rating or a `Saved` annotation if unrated, with tap-through access to the saved record and notes.
  - **AC6c:** Given more than three Kaggle matches exist, then the app displays the first three from `ORDER BY points DESC, winery ASC`. Winery alphabetical order is the deterministic tiebreak for tied or null points.
  - **AC-KaggleRanking-Nulls:** Given Kaggle matches for a country-province-variety are ranked for display, then every match with a real `points` score ranks above every match with a null score, regardless of winery name. Null is always the lowest tier and is never mixed among scored matches alphabetically. Winery ascending breaks ties only among matches with the same points value or among matches whose points are all null. Raw SQLite implements this correctly with `ORDER BY points DESC, winery ASC` because null values sort last for a descending column. If ranking occurs after retrieval in Kotlin or another layer, the comparator must implement nulls-last explicitly.
  - **AC6d:** Given web search returns options, then the app displays up to the first three usable results in the search engine's existing order without applying custom ranking. If fewer than three usable results exist, only those results render.

### Error Handling
- **AC7:** Given a cloud LLM call or live web search fails, when this occurs, then an inline error is shown in the thread with a retry option in the same casual tone. No fabricated content is displayed in place of the failed operation.
  - **AC7a:** A Kaggle query failure does not use this error pattern. It proceeds to the cache lookup and then the web fallback under AC10e or AC10f in the same way as a clean zero-match result; Kaggle never stops the response on its own.
  - **AC7b:** AC10j also does not use the retry pattern when the web search cannot be reached because the device has no internet connection and no cached option is available. A retry control is not shown because the same action cannot succeed until connectivity returns.
  - **AC7c:** AC10k does not use this error pattern when a cached option is usable. The cached card renders and the chain stops before web search.

### Empty States
- **AC8:** Given no query has been submitted yet, when the screen first loads, then an empty state invites the first query via input placeholder text. No fabricated example results are shown.

### Eventing
- **AC9:** Given a query is submitted, then log locally: query text length, routing decision, how many valid Gemma cards were produced, which Kaggle query path ran, whether Kaggle missed or failed technically, whether cached web data was used, whether live web search ran, whether web data was written to Room, the selected option source, and any winery-verification result. Personal-use MVP — local logging only, no analytics backend.

### Recommendation fallback chain
- **AC10:** A displayed card requires `name` and `country`; `winery` and `province` are optional. A clarification response under AC3d is not a Gemma failure. Gemma must return three valid cards to complete its layer. Fewer than three defers immediately to Kaggle. A Kaggle miss proceeds through cached web data to live web search. At least one card is required across the complete flow; otherwise the app displays the polite no-results response in AC10i.
- **AC10-offer-context:** Given the user accepts a broader-web offer after clarification, the next layer uses the complete accumulated request—not only the final short answer—and excludes every card already shown.
  - **AC10a:** Given variety, country, and province are all resolved, then Kaggle queries that exact combination. Any resolved `body`, `tannin`, or `acidity` value is applied as an additional filter. `flavor` is excluded from SQL equality filtering and is used only for display or ranking because it is multi-valued. Results remain ordered by points descending and winery ascending and limited to three cards.
  - **AC10b:** Given the preference-pool query returns no result, Kaggle uses every country, province, or variety value explicitly supplied by the user or shared by all Gemma options as structured search criteria. One shared key produces a one-field lookup, two shared keys produce a two-field lookup, and three shared keys produce a full country-province-variety lookup. If that structured lookup is unavailable or empty, Kaggle searches the ordinary `wine_reviews` columns using bound, case-insensitive keyword parameters because Android's bundled SQLite may not provide FTS5. The app first requires all meaningful terms from the accumulated user request and falls back to an any-term match only when the strict search is empty. If the request-only search is empty, the query expands with resolved Gemma wine names, wineries, countries, provinces, and varieties. User terms take priority over generated terms and Kaggle results remain ordered by points descending and winery ascending. Rosé searches are limited to the wine name and variety fields so tasting-note mentions of rose do not produce the wrong wine type. Resolved values may be backfilled from a Kaggle match under AC10d.
  - **AC10c:** Given the AC10a query returns one or more matches, then up to three option cards are shown under AC6 and a separate assistant message asks, "Would you like me to run a broader web search?"
  - **AC10d:** Given the AC10b query returns one or more matches, then country, province, and variety are backfilled from the matched Kaggle data, including any values Gemma had resolved before the keyword search, and up to three option cards are shown under AC6.
  - **AC10e:** Given the AC10a query returns zero matches or fails, then behavior depends on whether an attribute filter was applied. Without an attribute filter, the app proceeds directly to AC10g without retrying Kaggle with keywords; a technical failure is treated as a clean miss. With a `body`, `tannin`, or `acidity` filter, the app does not fall back to cache or web search. It instead explains in the thread that no match was found for the specified combination and names the applied attribute as the likely limiting factor. No retry control is shown.
  - **AC10f:** Given the AC10b query returns zero matches or fails to execute, then the app checks the cache when a complete country-province-variety is available; otherwise it proceeds to the web fallback in AC10g. A Kaggle technical failure is treated like a clean miss.
  - **AC10g:** Given Kaggle and the cache return no usable option, then the following web-search rules apply:
    - When one or more complete Gemma profiles exist, the app runs the full web search using their resolved country-province-variety information and the original request to find up to three options.
    - When no complete combination exists, the full web search runs using the original user-query keywords.
    - Brave search requests bottle-focused recommendation evidence and includes expanded result excerpts. Gemma returns three distinct supported options whenever the evidence contains at least three, otherwise every supported option available up to three. Each returned option receives its own independently synthesized `web_summary`. Rating and review summary remain unset for cached and newly found web options because both are reserved for a real Kaggle match; the Profile Page omits the Critic Review section for these options. This fallback is part of the MVP.
    - The generation that produces each `web_summary` also resolves that option's structured `body`, `tannin`, and `acidity` values. Prose and structured attributes come from one generation step rather than separate calls.
  - **AC10h:** Given AC10g performs a full live web search because no cached option exists, when it resolves new field-level data and options for a country-province-variety combination, then the app writes the profile and first returned option, including winery, `web_summary`, `body`, `tannin`, `acidity`, and other resolved fields, to `VarietyRegionProfile` with source `web_search`. The write replaces any row for the same `country` + `province` + `variety` composite key. Only that first-position result is cached; the other options remain in the session thread. No custom web ranking is applied. Web-derived `body`, `tannin`, and `acidity` values carry the internal trailing `*` thin-evidence marker.
  - **AC10i:** Given the web-search layer fails or returns no valid card after Gemma and Kaggle also failed, then the app politely states that it could not find relevant information for the request. It presents no fabricated card.
  - **AC10j:** Given AC10g requires a live web search but the device has no internet connection, then the response plainly explains that web search could not run without a connection and that Gemma, Kaggle, and the on-device cache did not contain enough information to answer accurately. The message uses the thread's casual tone and does not show a retry control.
  - **AC10k:** Given the cache returns three usable options, then they render and no live web search runs. Given it returns only one or two, they remain the current web-layer candidate while live search is checked for a larger set.
  - **AC10l:** Given Kaggle cards have displayed and the user accepts the broader-web offer, then live web search runs with the retained criteria and excludes every Gemma or Kaggle card already shown.

**Open assumptions:**
- Exact routing heuristic for cloud escalation (word count? explicit constraint count?) is not yet defined — needed before this can be built.
- Assumed cheese pairing is requested via a dedicated action, not by re-parsing free text for intent — confirm this matches your expectation.
- Exact styling and visual treatment of the wine option-card section within the chat response.

---

## 6A. Find

**Current design:** Find is the default tab after splash. Tab order: **Find, Chat, My List**. This supersedes the earlier Guided Selection layout, required variety/country/region choices, and ten-country shortlist.

### Layout and selection

The Find layout follows the supplied visual reference: parchment background, burgundy accents, a filter-count pill and Clear all action, followed by Type, Location, and Taste profile sections separated by thin rules. Search stays visible above the existing three-tab navigation while content scrolls. A helper link opens Chat.

| Category | Control and options |
|---|---|
| **Type** | Three equal-width columns, wrapping to a second row: Red, White, Sparkling, Rosé, Fortified. Hint: “select any that apply.” Sweet is now a Sweetness option rather than a Type. |
| **Location** | One outlined field displays Country · Province (or Any country / Any province). It opens a location bottom sheet with Country and Province pickers and Done. Each picker uses alphabetically sorted database values; Province narrows to the selected country. Selecting a value returns to the location sheet. |
| **Taste profile** | Bold section heading with “Optional — leave blank if you're not sure.” |
| **Sweetness** | Bone-Dry, Off-Dry, Sweet in three equal-width columns. |
| **Tannin** | Smooth, Moderate, Astringent in three equal-width columns. |
| **Body** | Light-Bodied, Medium-Bodied, Full-Bodied in three equal-width columns. |
| **Acidity** | Soft, Crisp, Tart in three equal-width columns. |

All option groups support multiple selections. Each entire allocated cell—including the circular indicator, label, and whitespace—is one toggle target, at least 48dp tall. There are no separate nested indicator click handlers. The indicator is an empty circle when off and a burgundy circle with a white check when on. Accessibility exposes each cell as a checked/unchecked multi-select control. Empty cells in an incomplete row retain their column width and are not actionable.

Every category is optional. Start with no selections. Tapping a selected cell deselects just that value. Clear all resets all selections and the count to zero, disabling Search; it does not silently re-run or overwrite prior results. Count each selected option plus one for an active Location. Country and Province remain single selections; clearing/changing Country clears Province. Persist and display the field as Province, with `USA` displaying the stored country value `US`.

Option labels come directly from the shared evidence-map keys in `FindPhraseEvidence`. Gemma uses those same descriptive values; the app's shared profile schema remains string-based, preserving existing values and ranges.

The location list is loaded directly from the bundled database, currently 43 countries and 425 distinct provinces. It is not limited to the earlier curated list. If loading fails, provide Retry locations; users can still search with Type or tasting preferences.

### Search and source behavior

- **GS-AC1:** Find opens by default after splash and displays the sections and controls described above. No Variety selector appears.
- **GS-AC2:** Within Location, tapping Country opens the alphabetically sorted country bottom sheet. Selecting a country updates the field and returns to the Location sheet.
- **GS-AC3:** Within Location, tapping Province opens an alphabetically sorted province sheet, narrowed by Country when selected. Selecting a province updates the field. Changing Country clears Province.
- **GS-AC4:** Unselected fields mean no preference and are excluded from both sources' constraints. They must not become hidden default filters.
- **GS-AC5:** Any single selection enables Search, including Type alone, Country alone, Province alone, or any one tasting preference. An entirely empty selection disables Search.
- **GS-AC6:** Search captures an immutable snapshot and starts on-device Gemma and Database searches independently. Both receive the same selected criteria. One source's completion, failure, or cancellation does not wait for or cancel the other.
- **GS-AC7:** Gemma produces at most one category recommendation consistent with all selected criteria. Each selected category is an allowed-value list: accept one selected value per category (OR within a category, AND across categories). It may choose an appropriate variety and fill unselected attributes with plausible model-generated values or Unknown. Validate every selected field, including Type, before displaying the recommendation. Reject contradictory/malformed output with a retryable error; a valid empty recommendation array is an empty result. Do not invent a winery, critic score, or review.
- **GS-AC8:** Database combines selected categories with AND and selected alternatives within a category with OR. Selected Country and Province use case-insensitive equality. Type uses case-insensitive equality against a union of explicit mapped `variety` values. Never infer Type from a wine name or review. An empty configured mapping returns no Database results without broadening the search. Tasting preferences use review-text phrase evidence. Return at most three matches, ranked by points descending, winery ascending, then ID ascending; null points always sort last. No keyword broadening or fallback runs.
- **GS-AC9:** Before searching, show guidance. After submission, Gemma and Database each render their own loading, results, or empty state as soon as ready.
- **GS-AC10:** Cards open the existing Profile Page with all source fields preserved. Gemma supplies Summary; Database supplies the full Critic Review and nullable critic score. Unavailable Database attributes remain Unknown, rather than copying search preferences as facts. No additional inference, profile-cache access, or web lookup runs when these profiles open, including from My List. Explicit Save remains available.
- **GS-AC11:** Each empty source independently shows “No matches for these selections.”
- **GS-AC12:** A Gemma failure provides Gemma-only Retry using the submitted criteria and a route to model setup. No cloud escalation occurs.
- **GS-AC13:** A Database read/parse failure presents the same empty state as zero matches; record a technical diagnostic internally.
- **GS-AC14:** Selection edits do not change existing results. Show the submitted criteria and a message when another Search is needed. A new search resets both sections and ignores late responses from obsolete searches.
- **GS-AC15:** Disable duplicate submission while the same criteria are running. Changed selections may start a new search. Retry cannot race a newer search.
- **GS-AC16:** Returning from Profile preserves selections and results without repeating the search. Buttons expose selected state; pickers support dismissal without changing values.

### Database matching limits

The implementation and review decisions are documented in [Find-Database-Mapping.md](Find-Database-Mapping.md), with compiled-query checks and row counts in [Find-Mapping-Audit.md](Find-Mapping-Audit.md).

The Type whitelist includes 64 actual variety values, covering 87.6% of database rows. All five visible types have backing data, including Sparkling and Fortified. The remaining 637 variety values are unclassified and stay searchable when Type is unselected; no style is guessed for them. Exact variety membership does not certify bottle style.

Tasting filters use the reviewed `FindPhraseEvidence` lists against `review_summary`. Firm tannins maps to Moderate; crisp acidity maps to Crisp. Risky bare terms such as rich, tart, tannic, dry, and sweet are excluded. Selected categories combine with AND; selected options and their phrases within one category combine with OR. Unknown option labels fail validation instead of silently dropping filters. Phrase matching remains approximate and may misread context or negation.

Show the disclaimer: **“Database preferences match descriptions in critic reviews; some wines may have no recorded match.”** Also state: **“Type filters use mapped varieties; unclassified wines may be missed.”**

### Retained product boundaries

On-device Gemma only; at most one Gemma card and three Database cards. No cloud/web fallback, `VarietyRegionProfile` access, or automatic History entries. Explicit Save uses My List. These rules are independent of Chat's fallback pipeline.


## 7. Profile Page

**Purpose:** Full-screen, distraction-free showcase of a single wine. Deliberate and factual in tone — the opposite register from Main Conversation's casual suggestions.

### Navigation & Display
- **AC1:** Given the user taps an option card, Gemma's own suggestion, or a My List entry, when the Profile Page opens, then it displays that specific wine's name, origin, and key traits in large typography, with no persistent navigation chrome.
- **AC2:** Given no bottle imagery is used in MVP scope, when the Profile Page renders, then typography carries the full visual weight, with no image or image placeholder.

### Data & Content — Key for Wine Description
- **AC4:** Given the user navigates to the Profile Page from a tapped option card or Gemma's own suggestion, when the page renders, then it displays the shared static field set from that entry with no source switch:
  - `variety`
  - `country`
  - `province`
  - `body` (single value | range | optional trailing `*` | Unknown)
  - `tannin` (single value | range | optional trailing `*` | Unknown)
  - `acidity` (single value | range | optional trailing `*` | Unknown)
  - `flavor_notes`
  - `winery`
  - `rating`
- **AC4a:** Given the entry came from a Kaggle option, when rendered, then winery, rating, and the full untruncated review summary display resolved values where the matched Kaggle record supplies them. When duplicate-wine aggregation combined multiple reviews, the labeled concatenation is displayed without dropping or shortening any review. The narrative section is labeled `Critic Review`; `Summary` and `Web Summary` are not displayed.
- **AC4b:** Find profiles are complete when their cards appear and do not trigger additional model inference, cache access, or web searches, including when reopened from My List. Given the entry came from Gemma's own suggestion without a matched option, when rendered, then its under-200-character descriptive text is displayed in a narrative section labeled `Summary`. `Critic Review` and `Web Summary` are not displayed, and rating remains absent because Gemma does not supply it.
- **AC4c:** Given the entry came from a cached or live web-search option under Section 6, AC10g, when rendered, then winery, variety, country, province, web summary, and other attributes display where the search resolved them. The narrative section is labeled `Web Summary`; `Summary` and `Critic Review` are not displayed. Rating remains absent because it is reserved for a real Kaggle match.
- **AC4d:** Given `body`, `tannin`, or `acidity` contains the internal trailing `*` thin-evidence marker, whether from fewer than three supporting Kaggle reviews or a web-derived synthesis, when the Profile Page renders that value, then it displays the plain-language annotation `Insufficient data` near the value using the same treatment for either source. The raw `*` character is not shown to the user. The exact caption, tooltip, or icon treatment remains to be defined.
- **AC5:** Given a shared attribute value is `Unknown`, when displayed, then it renders in muted or secondary text, visually distinct from resolved values.
- **AC7:** Given a Kaggle option's winery has been separately verified through a winery-verification web search, when the Profile Page renders, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
  - **AC7b:** The winery-verification search checks one specific Kaggle winery. It is separate from the category-data fallback web search in Section 6, AC10g, which retrieves options and resolves missing attributes after a Kaggle miss. The implementation must keep these as distinct search paths.
- **AC8:** Given the wine has a cheese pairing attached, when the Profile Page renders, then the pairing displays as a secondary section below the wine details, not as the primary focus.

### Actions
- **AC9:** Given the Profile Page is open, when it renders, then a `Suggested pairing` field and its concise content are visible upfront with the other profile details; no pairing action button is shown.
- **AC10:** Given the Profile Page is open, when the user scrolls its content, then the circular, center-aligned burgundy `Save` control remains fixed in the bottom navigation area. Tapping it saves the wine to My List (Section 8) and changes the control to a lighter muted state labeled `Saved`. Tapping `Saved` removes the wine and restores the burgundy `Save` state. The control and My List tiles do not display heart icons.
  - **AC10a:** Personal rating is display-only on the Profile Page. A saved rating displays as `Your rating · n / 10`; when absent, the page displays `You have not tried this wine` and provides no interactive rating scale.

---

## 8. My List

**Purpose:** The user's saved wines, with an optional personal rating and notes.

### Navigation & Display
- **AC1:** Given the user taps `Save` on the Profile Page (Section 7, AC10), when this happens, then the wine is added to My List, with rating and notes optional at that point. Simply viewing a suggestion or opening the Profile Page does not save it.
- **AC2:** Given My List contains at least one entry, when the My List screen opens, then entries are listed. **Sort order not yet defined — suggest most-recently-saved first, pending confirmation.**

### Data & Content
- **AC3:** Given a saved wine, when viewed in the list or its detail view, then the shared schema fields display alongside any personal rating and notes.
- **AC4:** Given the user opens a saved wine's detail, when they interact with the rating control, then they can set a rating from 1-10 (integer values).
  - **AC4a:** Given no rating has been set, when displayed, then the rating field shows as unrated — visually distinct both from a set 1-10 value and from the schema's own "Unknown" label (this is the user's own optional input, not a missing-data field).
- **AC5:** Given the user adds or edits a personal note, when saved, then the note persists with that My List entry across sessions.

### Data Synchronization
- **AC6:** Given this is a single-device personal MVP, when data is saved, then My List persists in local on-device storage only. No cloud sync in MVP.

### Error Handling
- **AC7:** Given a local storage write fails, when this occurs, then an inline error is shown and the save action does not silently fail.

### Empty States
- **AC8:** Given no saved wines exist yet, when My List opens, then an empty state invites saving a wine from the Profile Page.

**Open assumptions:**
- Sort order for My List — not yet defined.
- Whether removing a saved wine requires a confirmation step — assumed yes, pending your input.

---

## 9. Open Assumptions & Unresolved Decisions (Full List)

Surfaced here for validation before development starts:

1. Splash: maximum acceptable load duration before "taking longer than usual" messaging.
2. Splash model progress is resolved: network download uses byte percentage, while local checking and SHA-256 verification use indeterminate progress.
3. Main Conversation: exact routing heuristic for on-device → cloud escalation.
4. Main Conversation: cheese pairing assumed to be a dedicated action, not free-text intent parsing.
5. Main Conversation: exact styling and visual treatment of the wine option-card section.
7. Profile Page: exact typography and spacing for the source-specific `Summary`, `Critic Review`, and `Web Summary` sections.
8. Profile Page: exact caption, tooltip, or icon treatment for the `Insufficient data` annotation.
11. My List: sort order for the list.
12. My List: confirmation step assumed required before removing a saved wine.
13. **Scope confirmation:** location/price lookup (Google Places), discussed earlier in this project, is not part of these five MVP screens — confirm this is an intentional deferral.
14. Final system prompt. The current structured-output instruction is implementation scaffolding and has not been approved as the final product prompt.

---

## 10. Implementation Status

Status checked against the working code on 17 September 2026. A checked item is implemented and has passed the computer-only build or automated checks. Partially complete items have working foundations but still require the work stated beside them.

### Complete

- [x] Splash branding, authenticated resumable Gemma E2B download, burgundy byte-percentage progress, SHA-256 verification, three automatic retries, and manual retry state
- [x] Offline LiteRT-LM conversation inference after model installation
- [x] Shared Find, Chat, and My List navigation, headers, empty states, conversation thread, composer, and persistent in-session Chat state
- [x] Gemma hidden-profile schema uses `country`, `province`, and `variety`; `rating` and `confidence` are not requested from or populated by Gemma
- [x] Gemma prompt and parser support exactly three distinct lightweight records with identity fields and a short summary in one hidden `[WINE_CARDS]` JSON array
- [x] Gemma runs the fixed Q1 type, Q2 country, and Q3 attribute sequence; invalid or ambiguous answers keep the current question open while explicit no-preference answers close it
- [x] Lightweight per-turn Q1/Q2/Q3 field coverage replaces repeated full snapshots; one full preference snapshot is emitted only when all three questions close
- [x] Completed field coverage immediately hands the accumulated criteria to Kaggle, while clarification, digression, and off-domain turns never trigger a fallback source
- [x] Gemma's visible prose streams while generation continues; hidden structured data remains invisible and each option card appears as soon as its individual profile is complete
- [x] The Gemma prompt avoids repeated conversational fillers, with a narrow runtime filter removing a previously used opening filler if the model repeats it
- [x] Compound answers close every applicable question, corrections can reopen affected coverage, and answers already supplied are not asked again
- [x] Attribute clarification offers help with unfamiliar terms such as body, tannin, and acidity
- [x] Gemma produces structured recommendations when coverage completes and the app immediately queries the enthusiast database using the accumulated criteria
- [x] Kaggle cards trigger the broader-web offer; a Kaggle miss continues automatically and later layers exclude previously displayed cards
- [x] Failed Gemma or Kaggle output advances automatically; complete failure produces a polite no-relevant-information response
- [x] Gemma cards use lightweight identity payloads; longer attributes, pairing, and Summary are generated when the Profile Page opens
- [x] Bundled `wine_reviews.db` Kaggle asset, first-launch background extraction, copied-database validation, and read-only access
- [x] Kaggle exact lookup is case-insensitive and retries bound keyword matching with resolved Gemma criteria when exact spelling or optional lookup fields do not match
- [x] Kaggle exact country-province-variety lookup and Android-compatible original-query keyword lookup without a runtime FTS5 dependency
- [x] Kaggle misses with body, tannin, or acidity filters stop with a specific limiting-attribute explanation; exact-profile misses without attribute filters go directly to web search
- [x] Kaggle results include a separate satisfaction question; rejection sends the retained request and all clarification preferences directly to the web-search layer
- [x] Kaggle ranking uses `ORDER BY points DESC, winery ASC`, preserving SQLite's nulls-last behavior
- [x] Wine option cards display wine name, winery when available, and `Country, Province`; keyword matches backfill mandatory profile fields
- [x] Full, untruncated Kaggle `review_summary` is preserved through Find, Chat, My List, and the Profile Page
- [x] Suggestions retain their Gemma, Kaggle, or web-search origin through My List; the Profile Page displays only the matching `Summary`, `Critic Review`, or `Web Summary` section
- [x] `country_province_variety_profiles.json` is bundled and seeds all 4,119 profiles off the main thread when Room is empty
- [x] `VarietyRegionProfile` uses the `country` + `province` + `variety` composite key, JSON flavor-note conversion, exact lookup, replace-on-conflict inserts, and non-destructive Room migrations through database version 3
- [x] Room can store, retrieve, and replace one cached web option with its name, winery, pairing, resolved profile fields, and `web_summary`
- [x] Profile Page navigation from Find, Chat, and My List, with the shared profile fields and null-safe rating display
- [x] Persistent My List save and remove behavior
- [x] Computer-only debug APK build, test-APK compilation, lint, JVM recommendation-flow tests, seed parsing tests, and SQLite asset integrity checks

### Partially complete

- [~] Brave Search is connected as the final fallback after Gemma, Kaggle, and cache miss; a local `BRAVE_SEARCH_API_KEY` is still required before physical-device verification
- [x] Web-result write-back stores the first complete Brave/Gemma option for later cache reuse
- [x] Brave result excerpts are converted by on-device Gemma into structured web options with `web_summary`, body, tannin, and acidity where the evidence supports them
- [~] The current Gemma system instruction implements the approved two-stage card/profile contract, but remains pending physical-device response-quality testing
- [~] The Profile Page renders one source in the normal app flow, but the older AI/Kaggle comparison-toggle scaffold still exists in the screen code and must be removed to fully meet Section 7
- [~] My List stores existing personal-rating values, but the product flow for entering or editing a personal rating and notes is not implemented

### Not yet complete

- [ ] Production-safe Brave API credential delivery; the personal-development build currently reads the ignored `local.properties` value into `BuildConfig`
- [ ] Online, empty-result, technical-failure, and no-internet web-search responses defined in AC7 and AC10i-AC10j
- [ ] Cloud-LLM routing for complex or high-stakes requests
- [ ] Separate winery-verification web search and verified-winery badge data flow
- [ ] Local event logging defined in AC9
- [ ] Final product-owned system prompt
- [ ] Frank Ruhl Libre font bundling
- [ ] Personal-rating editing and notes in My List
- [ ] Final model-generated cheese-pairing experience
- [ ] Physical-device execution of instrumentation tests and final responsive visual QA

### Find implementation status — 17 September 2026

- [x] Default Find tab; three-column, full-cell multi-select controls, circular checks, filter count, Clear all, combined Location and Taste profile sections.
- [x] Alphabetical Country and Province bottom sheets populated from the full database catalog.
- [x] Any selection enables Search; only selected criteria reach both sources.
- [x] Independent Gemma and Database results, Gemma-only retry, and stale-response protection.
- [x] Type-aware database matching, tasting phrase evidence, stable top-three ranking, and null points last.
- [x] Full profile handoff and explicit Save without enrichment, automatic History, cloud/web fallback, or profile-cache access.
- [x] Verification: 49 local unit tests passed; debug APK and instrumented-test APK compiled; Android lint completed successfully.
- [ ] Visual emulator verification and live on-device Gemma quality evaluation; no physical-device test or reinstall is authorized by this change.

History has been removed from navigation and app behavior. New suggestions are not automatically recorded. Existing saved wines remain in My List; legacy history storage is not erased.
