# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Last updated:** 25 September 2026
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Chat opens with a Kotlin-owned mode choice — **curious about wines and pairings** or **find a wine** — rather than an open-ended greeting. Choosing "find a wine" drives a fixed, entirely Kotlin-owned Q1 (type) → Q2 (country/province) → Q3 (taste: body, tannin, acidity, sweetness) sequence: each answer is matched deterministically against curated keyword vocabularies (no model call), explicit lack-of-preference answers are valid, and an unrecognized answer either repeats the question with an apology or, when it reads as a genuine tangent or wine question, gets a brief on-device Gemma reply before the pending question is re-asked. Once Q1-Q3 all resolve, Kotlin builds the Kaggle query directly from the recorded preferences and runs it concurrently with a short-lived Gemma card-synthesis call; the cache is queried once Kaggle completes. Every response — hit or miss — is followed by a standing offer to run a broader web search, which only runs if accepted (AC10c). Newly resolved web-search profile data is written to the growing on-device `VarietyRegionProfile` Room table with source `web_search`, after checking it isn't already covered by Kaggle (AC10f). Choosing "curious about wines and pairings" instead hands the conversation to a persistent, open-ended Gemma chat scoped to wine topics; an explicit mid-chat request can switch from curious mode into the Q1-Q3 flow (the reverse direction is not currently implemented).

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
summary        (nullable string, Find's Gemma recommendation only, fewer than 200 characters)
review_summary (nullable string, full untruncated Kaggle review text)
web_summary   (nullable string, 1-2 sentences)
```

Critic `rating` is a nullable integer reserved for the Kaggle `points` field. It is not requested from or populated by Gemma.

`review_summary` is populated only when the app matches a real Kaggle review. It retains the full, untruncated review text with no sentence or character cap. Gemma and the web-search fallback never populate it. `web_summary` remains limited to a short AI-synthesized paraphrase.

`web_summary` is populated only for an option resolved through the web-search offer (AC10c), whether or not Kaggle/cache/Gemma already returned a match. It is an AI-synthesized paraphrase of the search findings, never verbatim source-page text. Gemma suggestions and Kaggle matches never populate it.

`summary` is populated only for **Find's** Gemma recommendation (Section 6A). It contains a few concise descriptive lines about the recommendation and stays below 200 characters. Kaggle and web-search options never populate it. **As of the 25 September 2026 progressive-card rework (Section 6, AC3), Chat's Gemma card search no longer requests or produces `summary` at all** — see the open item under Section 10 flagging that Chat-originated Gemma cards' Profile Page `Summary` section now always renders `Unknown` as a result, which needs a product decision (repurpose that section for Chat cards, or omit it for this source, rather than leaving it as a bare `Unknown`).

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

- Once Q1-Q3 close, Kotlin already holds the resolved `WinePreferences` (type, country/province, body, tannin, acidity, sweetness) and uses that recorded context — not a Gemma-parsed candidate list — to build the Kaggle and cache queries directly.
- Gemma's card synthesis and the Kaggle database query run **concurrently** the moment Q1-Q3 closes, mirroring how Find runs its "AI Sommelier" and "Database" sections independently (Section 6A). Kaggle never touches the on-device model, so this costs nothing; Gemma's cards are consulted only if Kaggle, the cache, and (if accepted) the web search all come up empty.
- Once the Kaggle query completes, the on-device cache is queried next, using the same recorded criteria. Web search is never run automatically — it is offered as a follow-up question, same as before, regardless of whether Kaggle/cache/Gemma already found something.
- Kaggle's `body`, `tannin`, and `acidity` filters match the database's precomputed columns directly (the same values Find matches, see Section 6A), not a free-text evidence search. A result is only described as "found in my database" when every criterion the user actually specified is satisfied; if the structured match instead comes from a looser keyword fallback (e.g. a country absent from the Kaggle dataset), the response says so ("close alternatives") rather than implying an exact hit.
- Kaggle options may supply a real wine name, winery, post-aggregation critic points, and full reviewer text. More than three matches are reduced using `ORDER BY points DESC, winery ASC`, making winery alphabetical order the deterministic tiebreak for tied or null points.
- Accepting the web-search offer runs the full web search: Brave returns its top three results, each result's title and snippet text has HTML tags/entities stripped and is capped at 300 characters before being handed to Gemma, keeping the evidence prompt small enough for reliable on-device synthesis. Newly found web options retain the search engine's result order. Each web option receives its own AI-synthesized `web_summary`. Web options never receive critic rating or review summary values.
- A web result is written to the cache only after two checks: it matches the recorded search context (whichever of country/province/variety/body/tannin/acidity are known — fields the user didn't specify aren't checked), using the DB column value or, when that field wasn't cleanly resolved, phrase evidence in the result's own summary text; and the same country/province/variety isn't already covered by a Kaggle row, so the cache isn't used to duplicate what Kaggle already serves natively.
- Each response renders one block per search type that actually ran — **Kaggle db**, **Cache db**, **Gemma**, **Web Search** — in the order each one resolved, separated from its neighbor by a thin line rule. A block's tag and status text (loading, "No results found," or "Here are a few options from …") sit outside any border; only the individual wine suggestions beneath them are containerized, each in its own bordered card, rather than one shared border wrapping the whole block. A block's header row shows the same three-dot pulsing loading indicator Find's "AI Sommelier" section uses while still in flight, uniformly across all four search types. Gemma's block is the exception to "status text in place of cards" while loading: it shows its already-tappable published cards (AC3-progressive) alongside a "Preparing wine N…" placeholder for each one still generating, rather than staying a single opaque loading line until all three are ready.
- Kaggle/cache cards must be tappable as soon as they render, even while Gemma (or the whole turn) is still in progress — a card must never be visible but inert.
- The web-search follow-up question ("Would you like me to run a broader web search?") is always its own separate chat bubble, never folded into the same bubble as the cards' background, whether it appears mid-stream (once Kaggle/cache have settled, without waiting on Gemma) or once the whole turn finishes.
- A web search that genuinely fails to complete (no network, or the request was interrupted, e.g. the user navigated away mid-search) renders a **Web Search** block with "Web Search could not be completed" and a **Try Again** action, instead of silently falling through to a fresh top-level turn — which previously surfaced as the confusing mode-choice greeting apology on the user's next message.
- A separate winery-verification web search may verify a specific Kaggle winery for the Profile Page badge.
- No citations or source-switch controls are displayed in the response and detail flow.

**Why this changed:** the original design synthesized Gemma's cards first and used their resolved fields as Kaggle/cache/web lookup context, waiting on that one Gemma call before anything else could run. On-device measurement showed two separate Gemma calls (card synthesis and web-result synthesis) contend for the same inference engine and can serialize behind each other if both are started eagerly — one measured run took ~91 seconds. Driving the query from Kotlin's own recorded preferences lets Kaggle and the cache answer in well under a second when they have a match, independent of Gemma's multi-second on-device latency, while Gemma still runs concurrently and is shown as its own section rather than skipped.

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
- The splash progress bar's minimum paced duration is implemented as a fixed 3.5 seconds (`MINIMUM_PREPARATION_MILLIS`, reduced from an earlier 5 seconds) before it can reach 100%; actual navigation still waits for real model preparation to finish if that takes longer. A separate "taking longer than usual" message for a slow load beyond this pacing is not yet defined.
- On-device path is settled on the pinned `gemma-4-E2B-it.litertlm` artifact and LiteRT-LM runtime. Download progress is byte-based; local checking and SHA-256 verification are indeterminate phases.

---

## 6. Main Conversation Screen

**Purpose:** Single chat-style interface, casual and personable in tone. The deterministic Q1-Q3 flow targets three option cards once it completes and requires at least one. Gemma's card synthesis and the Kaggle database query run concurrently the moment Q1-Q3 closes, using Kotlin's own recorded preferences rather than waiting on Gemma's output; the cache is queried once Kaggle completes, and the web-search layer runs only if offered and accepted. The detailed profile loads on the Profile Page.

### Navigation & Display
- **AC1:** Given the app has passed splash, when the Main Conversation Screen loads, then a persistent text input is displayed at the bottom of the screen.
  - **AC1a:** Given a new conversation opens, then UnCork briefly introduces itself as the user's personal sommelier and asks a fixed, Kotlin-owned mode choice: **curious about wines and pairings** or **find a wine**, offered as both tappable quick-reply buttons and matchable free text. This replaces the earlier design where the greeting asked directly about wine type.
  - **AC1b:** Given the launch greeting or any fixed Q1-Q3 question is already visible, then it is never regenerated by a model — this and every onboarding question in AC3 are fixed Kotlin copy, not Gemma output, so there is no risk of the app "introducing itself again."
- **AC2:** Given a prior response exists in the current session, when the screen renders, then the conversation thread displays above the input, most recent at the bottom.
  - **AC2a:** Given the app is closed and reopened (a new session), when the Main Conversation Screen loads, then the screen starts fresh — the previous session's suggestions are not shown inline, and only explicitly saved wines remain available in My List.
  - **AC2b:** Given the LLM can produce any number of suggestions within one session, when the user's query shifts to a different topic within the same session, then earlier suggestion cards remain visible in the thread rather than being cleared or collapsed.

### Content Tone
- **AC3:** Given the deterministic Q1-Q3 flow closes (all three questions resolved), then a short-lived Gemma call returns exactly three distinct card records as its own source section. As of the 25 September 2026 progressive-card rework, each record is a **complete profile the first time it's produced** — `name`, `variety`, `country`, `province`, `body`, `tannin`, `acidity`, `sweetness`, `flavor_notes`, and `suggested_pairing` — rather than a lightweight identity payload needing a separate stage-two call; the prompt no longer requests `summary` for Chat cards (see the Section 4 schema note and Section 10's flagged Profile Page gap). Gemma must choose options for which both name and country are known; neither mandatory field may be `Unknown`. Variety and province may be `Unknown`.
- **AC3-progressive:** Cards are parsed and published incrementally as Gemma's response streams in, not only once the full response finishes — the moment a card's JSON object closes (`CompleteJsonObjects`, tracking brace depth and string/escape state so a stray `{`/`}` inside a quoted value doesn't miscount), it's validated against the recorded `WinePreferences` (AC3k) and, if it passes, immediately published to the Gemma block's card list while the block's status stays `LOADING` until all three (or the stream ends) resolve. A card opened from this partial, still-loading list is a finished profile — the Profile Page must not queue another model call for it (see AC4a). Each request also writes one metadata-only timing record (`GemmaTimingTrace`, debug builds only — no prompts, preferences, or generated text) to `files/diagnostics/gemma-timings.log`, documented in `Docs/Gemma-Timing-Diagnostics.md`.
- **AC3a:** Given Q1, Q2, and Q3 all resolve, then the app builds the Kaggle query directly from the recorded `WinePreferences` — not from Gemma's output — and runs it concurrently with Gemma's card synthesis. Neither source waits on the other; Kaggle typically answers in well under a second regardless of Gemma's multi-second on-device latency.
- **AC3b:** Given all three fixed questions are answered (Q1: type, Q2: country/province, Q3: taste — body, tannin, acidity, sweetness), then the app proceeds immediately to the Kaggle search and Gemma's card synthesis together. There is no separate enthusiast-search confirmation step, and no model call is made to decide that the question set is complete — that decision is made entirely in Kotlin once each `FindWineStep` has a matched or explicitly-declined answer.
- **AC3c:** Given the short-lived Gemma card-generation call is streaming, then no partial prose is shown in the thread — the fixed Q1-Q3 questions and the mode-choice greeting are static Kotlin copy, not streamed model output. Each source (Gemma, Kaggle, cache, and — if accepted — web) renders its own tagged section with a loading indicator until it resolves, in the order each one actually finishes, rather than the thread waiting for every source before showing anything.
- **AC3d:** Given a new conversation begins, then the fixed greeting introduces UnCork and asks the Kotlin-owned mode choice (AC1a). Choosing "find a wine" starts the fixed Q1 (type) → Q2 (country/province) → Q3 (taste) sequence, driven entirely by Kotlin. Each answer is matched deterministically against curated keyword vocabularies (`ChatFlow.kt`, sourced from `Docs/Body.md`, `Docs/Tannin.md`, `Docs/Acidity.md`, `Docs/Sweetness.md`, and the app's own `WineRegions` country/province catalog) — no model call is made to interpret a Q1-Q3 answer. Onboarding questions and clarification responses produce no visible wine cards and do not trigger Kaggle or web search. Q2's country matching also accepts a demonym or short acronym for every one of the catalog's 43 countries (`COUNTRY_ALIASES` — e.g. "Chinese Red" resolves China, "Aus" resolves Australia, "Ind" resolves India), each vetted for collision risk against common English words before being added; `us` is deliberately excluded from this list despite "usa"/"america"/"american" being included, since it collides with the pronoun.
- **AC3e:** Since the fixed questions and their apology/repeat copy are static Kotlin strings (`ChatFlowText`), there is no conversational filler generated per turn and therefore no risk of a repeated filler phrase across onboarding turns. (This concern applies to the free-form "curious" chat mode instead — see AC3d-curious below.)
- **AC3e-1:** Given the mode-choice greeting or any Q1-Q3 question is displayed, then every selectable value is individually formatted in bold Markdown (e.g. **red**, **France**, **Light**, **Smooth**). This is fixed copy authored once in `ChatFlowText`, not per-turn model output, so this rule is enforced by construction rather than validated per response.
- **AC3f:** Given the user supplies `no preference`, `any`, `surprise me`, `not sure`, `none`, `nothing`, `skip`, or an equivalent uncertainty/lack-of-preference phrase (matched via `isNoPreference` against a fixed phrase list) at Q1, Q2, or Q3, then the app treats it as a valid answer and advances to the next question without demanding specificity. The declined field remains `Unknown` in the final preference snapshot passed to Gemma. A decline is tracked separately from an unanswered field (`declinedSteps`, alongside `WinePreferences`) so the question doesn't repeat — a prior bug conflated the two, since a declined step's fields are indistinguishable from a not-yet-asked step by value alone, causing the same question to repeat indefinitely after any decline on Q1, Q2, or Q3.
- **AC3g:** Given the user's answer to Q1, Q2, or Q3 doesn't match any recognized keyword and doesn't read as a real tangent or question (fewer than four words and no `?`), then the app repeats the current question with a fixed apology prefix ("I'm sorry I couldn't quite catch that. …") and asks no model to interpret the answer.
- **AC3g-1 — digression:** Given the unmatched answer instead reads as a likely tangent or wine question (contains `?`, or four or more words), then a brief, separate short-lived Gemma reply (scoped by the same system instruction as the curious chat mode) answers it, followed immediately by the still-pending fixed question repeated underneath. Coverage/progress through Q1-Q3 does not change.
- **AC3h:** Kotlin owns every onboarding decision: matching each answer, deciding when to advance, deciding when to apologize-and-repeat versus dispatch a digression reply, and deciding when all three questions have resolved. Gemma is never asked to classify, rewrite, or judge completeness of a Q1-Q3 answer. Gemma's only involvement before completion is the optional one-off digression reply in AC3g-1.
- **AC3i:** Session-only progress through Q1, Q2, and Q3 is held as plain Kotlin state (`FindWineStep`, `WinePreferences`) — there is no hidden per-turn model-emitted coverage block. This state is never written to Room and resets with the conversation session (a new session always restarts at the mode-choice greeting).
- **AC3j:** Because the fixed flow's questions and progress are pure Kotlin state rather than model output, there is no hidden marker content to strip from onboarding turns. (The free-form "curious" mode and the final card-generation call remain separate, short-lived Gemma calls whose raw output is parsed but never shown verbatim.)
- **AC3k:** Given the final short-lived Gemma call returns its three cards, then each card is checked against the resolved `WinePreferences` snapshot (type, country, province, body, tannin, acidity, sweetness) via `cardMismatchReasons`. An `Unknown`/unresolved preference field imposes no constraint. A mismatched card is discarded without replacement and the mismatch reason is logged.
- **AC3l:** Given the final card-generation call's response doesn't parse as valid structured card JSON, then no cards are fabricated from a near-miss — the app proceeds through the normal empty-Gemma-cards path into the Kaggle/cache/web fallback chain (Section 6, AC10).
- **AC3m:** Given a Q1-Q3 question presents multiple choices, then bold text identifies the real selectable values (e.g. **France**, **Light**, **Astringent**) — this is guaranteed by the fixed copy in `ChatFlowText`, not validated per response as it would be for model-generated text.
- **AC3n:** There is no separate recap-and-confirmation turn. Closing Q3 (matching or explicitly declining the taste question) completes the fixed sequence and triggers AC3b immediately. A user correction mid-flow is handled by the existing digression path (AC3g-1) rather than a dedicated "reopen a closed field" mechanism; revisiting an earlier answer is not currently supported once its question has advanced.
- **AC3n-1 — compound answers:** Given a reply resolves the step it was actually asked (e.g. "French Red" answering Q1), then every other not-yet-resolved step's matcher also runs against that same reply — a Type answer is checked against the Country and Taste matchers too, a Country answer against Taste — and any hit closes that question as well, skipping straight to the next open one (or finalizing immediately if none remain). This is a deliberate symmetric scope (every reply checked against every later question, not just the immediately-next one), chosen over a narrower "next question only" scope. **Trade-off:** a verbose Type or Country answer can occasionally contain an incidental taste-related word (e.g. "full" inside an unrelated sentence) and silently resolve — and thus skip — Q3 without the user having directly answered it. This is accepted, not mitigated.
- **AC3d-curious:** Given the user instead chose "curious about wines and pairings" at the mode choice, then the conversation hands off to a persistent, multi-turn Gemma chat scoped to wine topics via a dedicated system instruction (`curious_chat_instruction.txt`). A narrow runtime filter avoids repeating a previously used opening filler phrase within the same session. An explicit mid-chat request to find a wine (e.g. "let's find a wine") switches into the Q1-Q3 flow starting at Q1, discarding any prior curious-mode context.
- **AC3o — mode switch:** Given the user is inside the Q1-Q3 flow, an explicit request to switch modes is not currently handled by a dedicated matcher the way the curious→find-wine switch is; only curious-mode → find-wine mid-chat switching (AC3d-curious) is implemented today.

Field-coverage tracking as previously specified (hidden `[FIELD_COVERAGE]`/`[STATE_SNAPSHOT]` markers, per-turn Gemma-classified `clarify`/`closed` state) has been retired along with the Gemma-led onboarding it supported. The equivalent behavior — knowing when Q1, Q2, and Q3 have each resolved — is now plain Kotlin state (`FindWineStep`), decided without a model call, per AC3h and AC3i above.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC4a:** Given Gemma is asked for recommendations in Chat, then its hidden `[WINE_CARDS]` output contains a complete profile per card (`name`, `variety`, `country`, `province`, `body`, `tannin`, `acidity`, `sweetness`, `flavor_notes`, `suggested_pairing` — AC3/AC3-progressive); it does not generate winery, rating, review information, or (as of the same rework) a `summary`. Given a Chat-originated Gemma card's Profile Page opens, it is marked `profileComplete` the moment it's published (mid-stream or otherwise), so no further model call is queued regardless of whether `summary` is resolved — this supersedes the card's own `summary` field as the "already loaded" signal, since Chat cards no longer carry one. **Open gap:** because `summary` is genuinely never populated for a Chat card under this design, the Profile Page's `Summary` section (AC4b in Section 7) now always renders `Unknown` for these — the underlying data (flavor notes, pairing) is present and correct, but the dedicated narrative field is empty by construction, not by failure. Needs a product decision (see Section 4 and Section 10). For any card whose full profile genuinely isn't yet resolved (an older/legacy path), the separate on-device full-profile request remains bounded to 45 seconds; if it doesn't return in time, the page falls back to `Unknown` for the unresolved fields rather than leaving "Loading details…" on screen indefinitely. `suggested_pairing` remains `Unknown` unless the original request explicitly asked for a food or cheese pairing. Gemma never produces `winery`, `rating`, `review_summary`, `web_summary`, or `confidence` in the full profile.
- **AC5:** Given cheese is not requested, when a response is generated, then no cheese pairing is included by default.
  - **AC5a:** Given the user explicitly requests a cheese pairing, then a cheese suggestion is appended in the same casual tone.

### Wine options
- **AC6:** Given Gemma, Kaggle, the cache, or the web-search fallback returns one or more matches, when the app displays them, then the permitted number of option cards render inline in the chat thread, grouped under the tag of whichever source produced them. Each card shows the wine name; the winery when available; and origin formatted as `Country, Province`, displaying `Unknown` for either unresolved origin field. Critic rating remains available on the Profile Page and is not shown on the option card.
  - **AC6a:** Given the user taps an option card, when tapped, then the app navigates to the Profile Page with that option's full field set. Opening a card does not save it.
  - **AC6b:** Given an option matches a wine already in My List, when it appears in the thread, then it shows the existing personal rating or a `Saved` annotation if unrated, with tap-through access to the saved record and notes.
  - **AC6c:** Given more than three Kaggle matches exist, then the app displays the first three from `ORDER BY points DESC, winery ASC`. Winery alphabetical order is the deterministic tiebreak for tied or null points.
  - **AC-KaggleRanking-Nulls:** Given Kaggle matches for a country-province-variety are ranked for display, then every match with a real `points` score ranks above every match with a null score, regardless of winery name. Null is always the lowest tier and is never mixed among scored matches alphabetically. Winery ascending breaks ties only among matches with the same points value or among matches whose points are all null. Raw SQLite implements this correctly with `ORDER BY points DESC, winery ASC` because null values sort last for a descending column. If ranking occurs after retrieval in Kotlin or another layer, the comparator must implement nulls-last explicitly.
  - **AC6d:** Given web search returns options, then the app displays up to the first three usable results in the search engine's existing order without applying custom ranking. If fewer than three usable results exist, only those results render.
  - **AC6e — per-source progress:** Given the Q1-Q3 flow has just closed, then the response renders one block per source that actually ran (Gemma, Kaggle, Cache, and Web if the follow-up was accepted), tagged and shown in the order each source resolved, not a fixed order, with a thin line separating one block from the next. Its header row shows the same three-dot pulsing loading indicator Find's "AI Sommelier" section uses while still in flight, uniformly across all four search types (an earlier iteration used it only for Gemma). Kaggle/Cache/Web show a plain "Searching…" line while loading; Gemma's block instead shows its already-published cards (AC3-progressive) interleaved with a "Preparing wine N…" placeholder row (with its own small spinner) for each of the three cards not yet published — so a card the user can already tap sits above one still generating, rather than the whole block staying a single opaque loading state until all three are ready. A block that resolved with zero matches still renders, showing "No results found," rather than being omitted from the response. When multiple blocks have cards, the card(s) actually used for the response's summary text and tap-through favor Kaggle, then Cache, then Gemma, then Web, regardless of which block happened to finish first. Kaggle/cache cards are tappable the moment they render, independent of whether Gemma (or the whole turn) has finished — and, per AC3-progressive, so is an already-published Gemma card while its siblings are still generating.
  - **AC6f — web-search block failure:** Given the follow-up web search fails to complete (no network, or interrupted mid-request), then its block shows "Web Search could not be completed" with a **Try Again** action rather than the app silently falling through to a fresh top-level turn. Tapping **Try Again** resumes the same pending search (the same phrase pathway as an affirmative "yes" reply).

### Error Handling
- **AC7:** Given a cloud LLM call or live web search fails, when this occurs, then an inline error is shown in the thread with a retry option in the same casual tone. No fabricated content is displayed in place of the failed operation.
  - **AC7a:** A Kaggle query failure does not use this error pattern. It is treated as a clean zero-match result (an empty Kaggle section, per AC6e) and the cache is still queried; Kaggle never stops the response on its own.
  - **AC7b:** AC10h also does not use the retry pattern when the web search cannot be reached because the device has no internet connection and no cached option is available. A retry control is not shown because the same action cannot succeed until connectivity returns.
  - **AC7c:** A usable cached option (AC10b) renders as its own section without an error pattern; it does not by itself prevent the web-search offer in AC10c, which remains a standing offer regardless of what Gemma/Kaggle/cache found.

### Empty States
- **AC8:** Given no query has been submitted yet, when the screen first loads, then an empty state invites the first query via input placeholder text. No fabricated example results are shown.

### Eventing
- **AC9:** Given a query is submitted, then log locally: query text length, routing decision, how many valid Gemma cards were produced, which Kaggle query path ran, whether Kaggle missed or failed technically, whether cached web data was used, whether live web search ran, whether web data was written to Room, the selected option source, and any winery-verification result. Personal-use MVP — local logging only, no analytics backend.

### Recommendation fallback chain

**Current implementation (post-2026-09-24 rework):** the chain below replaced the earlier Gemma-cards-first design. It is a genuine four-source flow — Gemma, Kaggle, cache, web — rather than a single-winner sequence with the others treated as inaccessible fallbacks.

- **AC10:** A displayed card requires `name` and `country`; `winery` and `province` are optional. Q1-Q3 completion immediately starts Gemma's card synthesis and the Kaggle query together (AC3a/AC3b); the cache is queried once Kaggle completes, using the same recorded criteria regardless of whether Kaggle hit. If none of Gemma, Kaggle, or the cache returns a usable card, the app still responds (AC10d) and offers a broader web search (AC10c) rather than blocking on one further automatically.
- **AC10a:** Kaggle matches the `WinePreferences` recorded from Q1-Q3 directly against the database's precomputed columns — `wineType` against the same variety list Find uses (`WineTypeVarietyMap`), `country`/`province` by exact value, and `body`/`tannin`/`acidity` against the exact three-tier column values (`Light-Bodied`/`Medium-Bodied`/`Full-Bodied`, etc.) the same way Find does — not a free-text evidence search. Only fields the user actually answered constrain the query; an unanswered field is not checked. Results remain ordered by points descending and winery ascending and limited to three cards.
- **AC10b:** The cache lookup uses the same recorded criteria as Kaggle (`optionCache.findMatching`), independent of whether Kaggle hit — a cache hit is shown as its own section even alongside a Kaggle hit, rather than being skipped once Kaggle answers.
- **AC10c — web search is always offered, never automatic:** Whatever combination of Gemma/Kaggle/cache did or didn't return cards, the response ends with the same standing offer, "Would you like me to run a broader web search?" Accepting it is required to run a live web search; the app never runs one automatically as part of the Q1-Q3 handoff. This is deliberate: Gemma's card synthesis and its web-result synthesis are two separate on-device model calls that cannot run at the same time (the LiteRT-LM engine only serves one inference request at a time), so triggering a live web search automatically alongside card synthesis would queue one behind the other rather than parallelize them — measured at ~91 seconds in one case versus the sub-second response Kaggle/cache alone can give.
- **AC10d — "close alternatives" wording:** Given Kaggle (or, after accepting the web offer, the web search) returns cards that don't actually satisfy every criterion the user specified — e.g. a country absent from the Kaggle dataset falling back to a looser keyword match — the response says "While I couldn't find an exact match, I found some close alternatives that you might like" instead of implying a genuine, fully-matching hit. A result's `body`/`tannin`/`acidity` may satisfy this check either by an exact column match or by phrase evidence in its own summary text (the same sommelier synonym vocabulary Chat uses to parse a user's own Q3 answer) when the structured field isn't cleanly resolved — relevant mainly to web-synthesized cards, whose structured fields are not as reliably populated as Kaggle's precomputed columns.
- **AC10e — accepting the web offer:** Brave search requests bottle-focused recommendation evidence, fetching its top three results with HTML-stripped, 300-character-capped excerpts per result to keep the evidence prompt within the on-device model's reliable range. Gemma returns three distinct supported options whenever the evidence contains at least three, otherwise every supported option available up to three; a wine missing `name` or `country` in the model's output is skipped individually rather than discarding the whole batch, and a missing/unresolved field falls back to `Unknown` instead of failing the card. Each returned option receives its own independently synthesized `web_summary`. Rating and review summary remain unset for web options because both are reserved for a real Kaggle match; the Profile Page omits the Critic Review section for these options.
- **AC10f — writing to the cache:** A web result is written to `VarietyRegionProfile` (source `web_search`) only if it passes two checks: it matches the recorded search context (AC10d's matching rule) — not merely "are its fields non-`Unknown`" — and its exact country/province/variety isn't already covered by a Kaggle row (`findExact`), so the cache isn't used to duplicate what Kaggle already serves natively. The write replaces any existing row for that composite key. Only that one option is cached; other returned options remain in the session thread but are not persisted.
- **AC10g:** Given the web-search layer fails or returns no valid card after Gemma, Kaggle, and the cache also came up empty, then the app politely states that it could not find relevant information for the request. It presents no fabricated card.
- **AC10h:** Given the web search cannot reach the network (no connectivity) and no cached option is available, then the response plainly explains that web search could not run without a connection. The message uses the thread's casual tone and does not show a retry control.
- **AC10i — reply-phrase coverage:** The affirmative/negative reply matcher for the web-search offer accepts case-insensitive short forms in addition to the full words: `y`, `yup`, `yeah`, `ya`, `go`, `try again`, `retry` all count as affirmative; `n`, `na`, `nope`, `nah` all count as negative. A short reply that doesn't match either list previously fell through the pending-offer check entirely and was treated as a brand-new top-level message — surfacing the mode-choice greeting's apology instead of running (or declining) the search. `us`/`no` are deliberately excluded from these lists despite being short, common ways to answer: `us` collides with the pronoun (e.g. "suggest one for us" would wrongly count as a US country reference in the separate country-alias matcher, not this list, but the same false-positive risk applies to treating it as "yes"), and `no` alone collides with a negation prefix on an otherwise-substantive reply (e.g. "no, red please").

**Superseded and not currently reachable in this flow:** the previous design's keyword-expansion Kaggle retry (matching resolved Gemma wine names/wineries/countries/provinces/varieties when the exact structured query missed), its country/province/variety backfill from that retry, and its dedicated "the specified body/tannin/acidity appears to be the limiting factor" message are all still implemented (`KaggleConversationResponder.findKaggleThenWeb`/`findKaggleOptions`) but are no longer called from the Q1-Q3 completion path described above — Kaggle now answers from the recorded preferences alone, without a keyword-retry stage, and a Kaggle miss goes straight to the cache rather than a limiting-attribute message. This dead code path is a cleanup candidate, not a documented product behavior.

### Bugs fixed — 25 September 2026

- **Onboarding decline repeated the question forever.** Declining Q1, Q2, or Q3 with "no preference"/"none"/"nothing"/"skip" left that step's `WinePreferences` fields `Unknown` — indistinguishable, by field value alone, from "not yet asked" — so the same question kept re-appearing instead of advancing. Fixed by tracking declined steps separately (AC3f).
- **"Y" (and similar short replies) to the web-search offer broke the conversation.** A reply that matched neither the affirmative nor negative phrase list fell through the pending-offer check entirely and was routed to Gemma as a brand-new top-level message, whose mode had already reset to "undecided" — producing the confusing apology-plus-greeting reset instead of running or declining the search. Fixed by widening the accepted phrase lists (AC10i).
- **Kaggle/cache cards were visible but not clickable while Gemma was still generating.** The mid-stream synthetic bubble hard-coded a no-op click handler, so cards that had already resolved and rendered couldn't be tapped until the entire turn (including Gemma's card synthesis) finished.
- **The web-search follow-up question briefly rendered inside the cards' own bubble background** before reappearing correctly as a separate bubble once the turn finished. Fixed by always publishing it as its own bubble (AC10c).
- **Gemma's own block silently vanished from the response while the web-search question was being shown mid-turn.** The mid-turn publish rebuilt its source list from only the already-resolved sources, dropping a still-loading Gemma entirely rather than showing it as loading.
- **A web-search failure (no network, or the request was interrupted) was indistinguishable from "ran and found nothing,"** and either way cleared the pending-offer state — so the user's next message was treated as a fresh top-level turn, surfacing the same mode-choice apology bug described above. Fixed by a dedicated failed state with a Try Again action (AC6f, AC10i).
- **The Profile Page's "Loading details…" text could persist indefinitely** if the on-device full-profile inference call stalled — the call had no timeout and ran under `NonCancellable`, so nothing could ever un-stick it. Bounded to 45 seconds (AC4a).
- **The Profile Page re-ran the full profile inference on every visit to the same wine,** even when Chat's own stage-one Gemma response already included a usable summary, because the "already loaded" flag was never actually set on a successful load. Fixed on both ends: the flag is now set, and the reload is skipped entirely when a usable summary is already present (AC4a).
- **India was missing from the country catalog** (a Q2 answer of "India" fell through to the apology/repeat path); added, bringing the catalog to 43 countries. Demonym coverage was later found to be incomplete (12 of 43 countries) and extended to all of them, plus a small set of vetted short acronyms (AC3d).
- **Two Gemma prompts asked for `body`/`tannin`/`acidity` in a vocabulary (`light|medium|full`, `low|medium|high`) the shared parser never matched**, silently discarding those fields to `Unknown` on every web search and Profile Page detail load.

### Performance rework — 25 September 2026 (progressive card streaming)

On-device timing diagnostics (`Docs/Gemma-Timing-Diagnostics.md`) measured Chat's card-search call taking 43-51 seconds end to end on the Pixel 10 Pro Fold (`Docs/Gemma-Benchmark-2026-09-25.md`), with all three cards appearing at once only once the entire response finished. Two changes address this:

- **Cards now publish incrementally as each one finishes streaming**, rather than waiting for the whole three-card response (AC3-progressive). A card is fully validated and marked `profileComplete` the moment its JSON object closes, using a brace/string-aware incremental scanner (`CompleteJsonObjects`) that's chunk-boundary-safe (tested by feeding the same input split at every possible position). The measured run showed the first card appearing around 16-18 seconds in, rather than waiting the full 43-51 seconds for all three.
- **`summary` was dropped from Chat's card-search prompt** (`chat_search_instruction.txt`), and each card now asks for its full profile (`flavor_notes`, `suggested_pairing`) up front instead of a lightweight identity payload needing a separate stage-two call. A same-backend before/after comparison (`Docs/Gemma-Benchmark-No-Summary-2026-09-25.md`) showed total card-search time dropping from 43.4s to 31.2s (28.1%) in one paired run — explicitly documented as a single-run comparison, not a proven stable gain, since thermal state and sampled output weren't controlled between runs.
- Both benchmark documents are single-run, uncontrolled comparisons (no repeated trials, no thermal/GPU-clock controls) — useful as a before/after signal, not as a statistically rigorous measurement.

### Trade-offs

- **Compound Q1-Q3 answer matching (AC3n-1)** uses a symmetric scope — every reply is checked against every not-yet-resolved later question — chosen deliberately over a narrower "next question only" scope despite a higher false-positive surface (an incidental word in a Type/Country answer can silently resolve Taste). Narrowing this later would need explicit re-scoping, not just a bug fix.
- **`us` and bare `no` are excluded from the reply-phrase and country-alias matchers** (AC3d, AC10i) despite being short, common ways to answer, because both collide with ordinary English words/negations in ways that would misfire on unrelated replies. The unambiguous longer forms (`usa`, `america`, `american`, `no preference`, `nope`, `nah`) remain covered; this is a deliberate coverage/safety trade-off, not an oversight.
- **The 45-second Profile Page load timeout (AC4a)** bounds how long "Loading details…" can show, but a genuinely slow (not hung) device could still hit it and fall back to `Unknown` for a summary that would have arrived a few seconds later. No retry action is offered for this specific timeout today.
- **The web-search retry action (AC6f, AC10i) reuses the existing turn-based affirmative-reply pathway** ("Try Again" is sent as an ordinary chat message matched against the same phrase list) rather than a dedicated retry state machine — simpler to reason about and test, at the cost of the retry being one more conversational turn rather than an in-place re-fetch.
- **Dropping `summary` from Chat's card-search prompt (AC3, AC4a) to cut latency means the Profile Page's `Summary` section is now unconditionally `Unknown` for every Chat-originated Gemma card** — not a bug, but an unresolved consequence of the trade-off: the field genuinely isn't requested anymore, versus a stage-two call that could still fill it in at the cost of the latency this change was meant to remove. This needs a product decision — leave `Summary` empty for Chat cards (and hide or relabel the section rather than showing a bare `Unknown`), or accept the extra latency for that one field.

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
| **Type** | A tile that opens a bottom sheet listing Red, White, Sparkling, Rosé, Fortified as a single-select vertical list, plus an "Any type" row. Tapping a value applies it and closes the sheet immediately — there is no "select any that apply" multi-select mode and no Done button. Sweet is a Sweetness option rather than a Type. |
| **Country** and **Province** | Two independent tiles side by side, each opening its own single-select bottom sheet (not one shared "Location" sheet). Each list is alphabetically sorted from the bundled database, with an "Any country"/"Any province" row at the top; Province narrows to the selected Country. Selecting a value applies it and closes that field's sheet immediately. Changing Country clears Province. |
| **Taste profile** | Bold section heading with “Optional — leave blank if you're not sure.” |
| **Sweetness** | Bone-Dry, Off-Dry, Sweet, single-select vertical list with an "Any sweetness" row. |
| **Tannin** | Smooth, Moderate, Astringent, single-select vertical list with an "Any tannin" row. |
| **Body** | Light-Bodied, Medium-Bodied, Full-Bodied (displayed without the "-Bodied" suffix), single-select vertical list with an "Any body" row. |
| **Acidity** | Soft, Crisp, Tart, single-select vertical list with an "Any acidity" row. |

Every field on the Find form — Type, Country, Province, Sweetness, Tannin, Body, and Acidity — is single-select: choosing a value replaces whatever was selected before rather than adding to it, and immediately closes the bottom sheet. There is no Done button on any field's sheet. Each option row is a horizontal-divider-separated single-select radio row (a 20dp indicator circle plus label, `bodyMedium` typography, a 48dp minimum tap target), identical in structure and sizing across every field's sheet so height, font, and spacing cannot drift between fields. Accessibility exposes each row as a radio-button control. This is a change from the earlier multi-select, three-column checkbox-grid design: every field previously supported selecting several values at once per category (OR within a category); this is retired in favor of one value per field.

Every category is optional. Start with no selections. Tapping the "Any {field}" row clears that field. Clear all resets all selections and the count to zero, disabling Search; it does not silently re-run or overwrite prior results. Count each selected option plus one for an active Country or Province selection. Country and Province remain single selections; clearing/changing Country clears Province. Persist and display the field as Province, with `USA` displaying the stored country value `US`.

Option labels come directly from the shared evidence-map keys in `FindPhraseEvidence`. Gemma uses those same descriptive values; the app's shared profile schema remains string-based, preserving existing values and ranges.

The location list is loaded directly from the bundled database, currently 43 countries and 425 distinct provinces. It is not limited to the earlier curated list. If loading fails, provide Retry locations; users can still search with Type or tasting preferences.

### Search and source behavior

- **GS-AC1:** Find opens by default after splash and displays the sections and controls described above. No Variety selector appears.
- **GS-AC2:** Tapping the Country tile opens the alphabetically sorted country bottom sheet directly (there is no separate combined "Location" sheet). Selecting a country applies it and closes the sheet.
- **GS-AC3:** Tapping the Province tile opens an alphabetically sorted province sheet, narrowed by Country when selected. Selecting a province applies it and closes the sheet. Changing Country clears Province.
- **GS-AC4:** Unselected fields mean no preference and are excluded from both sources' constraints. They must not become hidden default filters.
- **GS-AC5:** Any single selection enables Search, including Type alone, Country alone, Province alone, or any one tasting preference. An entirely empty selection disables Search.
- **GS-AC6:** Search captures an immutable snapshot and starts on-device Gemma and Database searches independently. Both receive the same selected criteria. One source's completion, failure, or cancellation does not wait for or cancel the other.
- **GS-AC7:** Gemma produces at most one category recommendation consistent with all selected criteria. `GuidedCriteria`'s underlying fields remain `Set<String>` (so a query engine given several values in one category would still be evaluated as OR-within-category, AND-across-categories), but since the Find UI is now single-select per field (see Layout and selection above), in practice each category ever supplies zero or one value. It may choose an appropriate variety and fill unselected attributes with plausible model-generated values or Unknown. Validate every selected field, including Type, before displaying the recommendation. Reject contradictory/malformed output with a retryable error; a valid empty recommendation array is an empty result. Do not invent a winery, critic score, or review.
- **GS-AC8:** Database combines selected categories with AND; a category with more than one supplied value (not reachable from the current single-select UI, but still supported by the query layer) combines its alternatives with OR. Selected Country and Province use case-insensitive equality. Type uses case-insensitive equality against a union of explicit mapped `variety` values. Never infer Type from a wine name or review. An empty configured mapping returns no Database results without broadening the search. Tasting preferences use review-text phrase evidence. Return at most three matches, ranked by points descending, winery ascending, then ID ascending; null points always sort last. No keyword broadening or fallback runs.
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
- **AC4c:** Given the entry came from a cached or live web-search option under Section 6, AC10e, when rendered, then winery, variety, country, province, web summary, and other attributes display where the search resolved them. The narrative section is labeled `Web Summary`; `Summary` and `Critic Review` are not displayed. Rating remains absent because it is reserved for a real Kaggle match.
- **AC4d:** Given `body`, `tannin`, or `acidity` contains the internal trailing `*` thin-evidence marker, whether from fewer than three supporting Kaggle reviews or a web-derived synthesis, when the Profile Page renders that value, then it displays the plain-language annotation `Insufficient data` near the value using the same treatment for either source. The raw `*` character is not shown to the user. The exact caption, tooltip, or icon treatment remains to be defined.
- **AC5:** Given a shared attribute value is `Unknown`, when displayed, then it renders in muted or secondary text, visually distinct from resolved values.
- **AC7:** Given a Kaggle option's winery has been separately verified through a winery-verification web search, when the Profile Page renders, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
  - **AC7b:** The winery-verification search checks one specific Kaggle winery. It is separate from the category-data web search in Section 6, AC10c/AC10e, which the user opts into and which resolves missing attributes. The implementation must keep these as distinct search paths.
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

Status checked against the working code on 25 September 2026, including the Chat recommendation-flow rework and per-source master-card UI redesign described in Sections 4 and 6 (PR #17 and PR #18, both merged to `Master`) and the progressive card-streaming/timing-diagnostics performance rework (branch `codex/work-2026-09-25`, not yet merged to `Master` as of this update). A checked item is implemented and has passed the computer-only build or automated checks. Partially complete items have working foundations but still require the work stated beside them.

### Complete

- [x] Splash branding, authenticated resumable Gemma E2B download, burgundy byte-percentage progress, SHA-256 verification, three automatic retries, and manual retry state
- [x] Offline LiteRT-LM conversation inference after model installation
- [x] Shared Find, Chat, and My List navigation, headers, empty states, conversation thread, composer, and persistent in-session Chat state
- [x] Gemma hidden-profile schema uses `country`, `province`, and `variety`; `rating` and `confidence` are not requested from or populated by Gemma
- [x] Chat opens with a fixed, Kotlin-owned mode choice (curious about wines and pairings vs. find a wine) instead of an open-ended greeting or a direct wine-type question
- [x] Onboarding is a deterministic Kotlin state machine (`ChatFlow.kt`, `FindWineStep`): Q1 (type) → Q2 (country/province) → Q3 (taste: body, tannin, acidity, sweetness), each answer matched against curated keyword vocabularies with no model call; explicit no-preference answers close a question, an unrecognized short answer repeats the question with an apology, and a likely tangent/question gets a brief separate Gemma reply before the pending question is re-asked
- [x] The former Gemma-led hidden-marker field-coverage mechanism (`[FIELD_COVERAGE]`/`[STATE_SNAPSHOT]`) is retired; Q1-Q3 completion is decided entirely in Kotlin
- [x] Q1-Q3 completion runs Gemma's card-generation call and the Kaggle query concurrently, both driven from the same recorded `WinePreferences` (Kaggle never waits on Gemma's output); onboarding, apology, and digression turns never trigger a fallback source
- [x] The final card-generation Gemma call and the free-form "curious" chat mode are each short-lived/persistent conversations respectively; hidden structured data remains invisible in the thread
- [x] The curious-mode Gemma prompt avoids repeated conversational fillers, with a narrow runtime filter removing a previously used opening filler if the model repeats it
- [x] Compound Q1-Q3 answers (e.g. supplying type and country together) close every applicable question in one turn; mid-flow corrections are handled via the digression path rather than a dedicated reopen-a-closed-field mechanism
- [x] Q3's taste question presents body, tannin, acidity, and sweetness together, each with a one-word plain-language annotation (weight, dryness, sourness, sugar) and burgundy-styled heading, rather than an offer to explain unfamiliar terms on request
- [x] The Kaggle query is built directly from the recorded `WinePreferences`, matching the precomputed `body`/`tannin`/`acidity` columns and the same `WineTypeVarietyMap` variety list Find uses — not a mismatched free-text evidence vocabulary, and not a JSON candidate list read back out of Gemma's own output
- [x] Once Kaggle completes, the on-device cache is queried using the same recorded criteria, independent of whether Kaggle hit; the broader-web-search offer is always shown afterward (not only on a Kaggle hit) and only runs a live search if accepted — deliberately not automatic, since a live web search needs its own Gemma call that cannot run at the same time as card synthesis on the same on-device engine
- [x] Failed Gemma or Kaggle output advances automatically (an empty section, not an error); complete failure across Gemma/Kaggle/cache/accepted-web-search produces a polite no-relevant-information response
- [x] Each response renders one tagged section per source that actually ran (Gemma/Kaggle/Cache/Web), in true resolution order, with a loading indicator for a section still in flight and an explicit "No results found" for one that resolved empty, rather than a single result silently chosen among sources
- [x] A result that doesn't actually satisfy every criterion the user specified (e.g. a country-only keyword match) is labeled "close alternatives" rather than implied as an exact database hit; the match check accepts phrase evidence in a result's own summary text (reusing Chat's Q3 sommelier-synonym vocabulary) when a result's structured body/tannin/acidity field isn't cleanly resolved
- [x] A live web result is only written to the cache after confirming it matches the recorded search context and that Kaggle doesn't already cover the same country/province/variety, so the cache isn't populated with duplicates of what Kaggle already serves
- [x] Gemma cards use lightweight identity payloads; longer attributes, pairing, and Summary are generated when the Profile Page opens
- [x] Bundled `wine_reviews.db` Kaggle asset, first-launch background extraction, copied-database validation, and read-only access
- [x] Kaggle exact country-province-variety lookup and Android-compatible original-query keyword lookup without a runtime FTS5 dependency (`findKaggleThenWeb`/`findKaggleOptions` — this bound-keyword retry, its limiting-attribute-explanation message, and its Gemma-candidate-based backfill still exist in code but are no longer reached from the Q1-Q3 completion path; see the "Superseded" note in Section 6's fallback chain)
- [x] The broader-web-search offer ("Would you like me to run a broader web search?") is shown after every Q1-Q3 completion response, not only a Kaggle hit; accepting it sends the retained request and recorded preferences to the web-search layer
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
- [x] Brave Search verified end-to-end on a physical device as the final fallback after Kaggle and cache miss: the raw Brave call, HTML-stripped/300-character-capped evidence, and Gemma's web-option synthesis were confirmed to return usable cards in a live broader-web-search request
- [x] Web-search evidence handed to Gemma is capped to Brave's top three results (previously twelve) with HTML tags/entities stripped from each snippet, keeping the synthesis prompt small enough for reliable on-device output
- [x] A single malformed or incomplete wine entry in Gemma's synthesized web-result JSON no longer discards the whole batch; each entry is parsed independently and a missing `name`/`country` falls back rather than failing the card
- [x] The Find bottom sheet's Acidity options bug (a `when`-branch mis-grouping that always rendered an empty options list for Acidity) is fixed
- [x] Every Find field (Type, Country, Province, Sweetness, Tannin, Body, Acidity) is single-select with an "Any {field}" clear row, auto-dismissing on selection, with no Done button; all fields share one row-rendering implementation so height, font, and spacing cannot drift between fields
- [x] Find's search-result cards replace the "View profile" text link with a trailing chevron, matching the affordance used on Chat's suggestion cards
- [x] The debug latency annotation shown under an assistant reply (debug builds only) is reduced to just time-to-first-word, dropping the full per-stage timing breakdown from the UI; backend stage-timing recording (`DebugLatencyLog`) is unchanged
- [x] India added to the Find/Chat country catalog (`WineRegions.catalog`, sourced from Wikipedia's list of wine-producing regions), bringing the catalog to 43 countries; a Q2 answer of "India" previously fell through to the apology/repeat path because it had no catalog entry
- [x] Two Gemma prompts (web-result synthesis, wine profile) were asking for `body`/`tannin`/`acidity` as `light|medium|full`/`low|medium|high`, which the shared JSON parser never matched against the canonical `Light-Bodied`/`Smooth`/`Crisp`-style labels it accepts — meaning those fields were silently discarded to `Unknown` on every web search and every Profile Page detail load. Both prompts now ask for the same canonical labels the card-generation prompt and Find's Gemma prompt already used correctly
- [x] Splash's minimum progress-bar pacing reduced from 5 seconds to 3.5 seconds (`MINIMUM_PREPARATION_MILLIS`)
- [x] Compound Q1-Q3 answers genuinely close multiple questions in one turn (`isStepResolved`/`nextUnresolvedStep`/`applyOpportunisticMatches`), using the symmetric every-later-step scope described in AC3n-1
- [x] A decline ("no preference"/"none"/"nothing"/"skip") on any of Q1-Q3 is tracked separately from an unanswered field (`declinedSteps`), fixing an infinite question-repeat bug (AC3f)
- [x] `isNoPreference` recognizes `none` and `nothing` in addition to the previously-covered phrases
- [x] Every one of the 43 catalog countries has at least one demonym alias (previously only 12 did), plus vetted short acronyms (`aus`, `ind`) where they don't collide with common English words; `us` is deliberately excluded (AC3d)
- [x] The web-search offer's affirmative/negative reply matcher accepts short forms (`y`/`yup`/`yeah`/`ya`/`go`/`try again`/`retry`, `n`/`na`/`nope`/`nah`) case-insensitively, fixing the "Y" reply bug (AC10i)
- [x] Chat's per-search-type response is redesigned into one block per source (Kaggle db, Cache db, Gemma, Web Search), each showing its tag and status text outside any border, with only the individual wine suggestion cards beneath containerized in their own bordered box, separated from the next block by a thin line rule (AC6e)
- [x] Every search-type block's loading state uses the same three-dot pulsing indicator Find's "AI Sommelier" section uses, unified across Kaggle/Cache/Gemma/Web Search (an earlier iteration used it only for Gemma, a plain spinner for the rest)
- [x] Kaggle/cache cards are clickable as soon as they render during streaming, not only once the whole turn (including Gemma) finishes
- [x] The web-search follow-up question always renders as its own separate chat bubble, both mid-stream (once Kaggle/cache settle, without waiting on Gemma) and in the final committed response, never inside the cards' own bubble background
- [x] Gemma's block no longer disappears from the response while the web-search question is shown mid-turn (a still-loading source is now included as `LOADING` in that mid-turn publish, not dropped)
- [x] A web-search failure (no network, or an interrupted request) renders a dedicated "Web Search could not be completed" block with a Try Again action, instead of the pending-offer state being silently cleared and the next message falling through to the mode-choice apology (AC6f)
- [x] No blanket top-of-bubble summary text for the recorded-preferences response ("I searched my database and found these matches from other wine enthusiasts.") — each block's own tag and text carries that information now
- [x] `MAX_SUMMARY_CHARACTERS` raised from 199 to 220 (200 characters plus a 10% buffer) to match the product's intended cap without hard-truncating a slightly-over model response mid-word
- [x] The Profile Page skips the redundant full-profile Gemma reload when Chat's own stage-one response already included a usable summary; `profileComplete` is now actually set to `true` on a successful reload (previously never set, causing a fresh reload on every revisit to the same wine)
- [x] The Profile Page's full-profile reload is bounded to 45 seconds (`withTimeoutOrNull`, safe inside the call's outer `NonCancellable` scope since it's a self-contained child coroutine) so a stalled on-device inference call can no longer leave "Loading details…" showing indefinitely
- [x] Chat's Gemma card search publishes each card incrementally as its JSON object finishes streaming (AC3-progressive), rather than waiting for the full three-card response — implemented via a chunk-boundary-safe brace/string-aware scanner (`CompleteJsonObjects`), tested by splitting the same input at every possible chunk boundary
- [x] Each Chat card is a complete profile (`flavor_notes`, `suggested_pairing` included) the moment it's published, marked `profileComplete` immediately so the Profile Page never queues a redundant reload for it, whether or not `summary` (no longer requested for Chat cards) is resolved (AC3, AC4a)
- [x] Per-request Gemma timing diagnostics (`GemmaTimingTrace`, debug builds only, metadata-only — no prompts/preferences/generated text) recorded to `files/diagnostics/gemma-timings.log` and Logcat, documented in `Docs/Gemma-Timing-Diagnostics.md`; on-device benchmarks in `Docs/Gemma-Benchmark-2026-09-25.md` and `Docs/Gemma-Benchmark-No-Summary-2026-09-25.md`
- [x] `SourceProgress` (Chat's per-source-block publisher) carries partial/in-flight card lists per source, not just a loading/complete boolean, so a block can show its already-published cards while still marked `LOADING`

### Partially complete

- [~] Production-safe Brave API credential delivery remains outstanding; the personal-development build still reads the ignored `local.properties` value into `BuildConfig`
- [~] The current Gemma system instruction implements the approved two-stage card/profile contract, but remains pending physical-device response-quality testing
- [~] The Profile Page renders one source in the normal app flow, but the older AI/Kaggle comparison-toggle scaffold still exists in the screen code and must be removed to fully meet Section 7
- [~] My List stores existing personal-rating values, but the product flow for entering or editing a personal rating and notes is not implemented

### Not yet complete

- [ ] Online, empty-result, technical-failure, and no-internet web-search responses defined in AC7 and AC10g-AC10h
- [ ] Cloud-LLM routing for complex or high-stakes requests
- [ ] Separate winery-verification web search and verified-winery badge data flow
- [ ] Local event logging defined in AC9
- [ ] Final product-owned system prompt
- [ ] Frank Ruhl Libre font bundling
- [ ] Personal-rating editing and notes in My List
- [ ] Final model-generated cheese-pairing experience
- [ ] Physical-device execution of instrumentation tests (`androidTest`) and final responsive visual QA — the Chat recommendation-flow rework's backend timing was confirmed live on a physical device via logcat (a Kaggle hit resolves in ~130ms; the Gemma/web mutex-contention regression is fixed), but the redesigned per-search-type master-card UI (bordered wine cards, line separators, the unified three-dot loader, the web-search-failure retry card) has not had a full visual walkthrough, and `androidTest` still has not been run (only compiled) for lack of an emulator/AVD — this includes the 45-second Profile Page load timeout and the progressive card-streaming UI, both untested against genuine on-device inference latency
- [ ] **Product decision needed:** the Profile Page's `Summary` section is now unconditionally `Unknown` for every Chat-originated Gemma card, since `summary` was deliberately dropped from Chat's card-search prompt to cut latency (see Section 4, Section 6 AC4a, and the "Dropping `summary`…" trade-off). Find's Gemma recommendation is unaffected — it still requests and displays `summary` normally. Needs a decision: leave the section empty/relabeled for Chat cards, or accept the added latency of a dedicated summary call for that one field.
- [ ] Both progressive-card-streaming benchmark documents are single uncontrolled runs (no repeated trials, no thermal/GPU-clock control) — the reported ~28% latency improvement from dropping `summary` needs repeated-trial confirmation before being treated as a proven, stable gain rather than a promising single-run signal

### Find implementation status — 20 September 2026

- [x] Default Find tab; single-select bottom sheets (radio rows with an "Any {field}" clear option, auto-dismiss on selection, no Done button) for Type, Country, Province, Sweetness, Tannin, Body, and Acidity, replacing the earlier three-column multi-select checkbox grid; filter count and Clear all retained.
- [x] Alphabetical Country and Province bottom sheets populated from the full database catalog, each opened from its own independent tile rather than a shared "Location" sheet.
- [x] Acidity options bug fixed (was always empty due to a `when`-branch mis-grouping); all seven fields share one row-rendering implementation for consistent height, font, and separator spacing.
- [x] Search-result cards use a trailing chevron instead of a "View profile" text link.
- [x] Any selection enables Search; only selected criteria reach both sources.
- [x] Independent Gemma and Database results, Gemma-only retry, and stale-response protection.
- [x] Type-aware database matching, tasting phrase evidence, stable top-three ranking, and null points last.
- [x] Full profile handoff and explicit Save without enrichment, automatic History, cloud/web fallback, or profile-cache access.
- [~] Instrumented Compose UI test (`GuidedSelectionScreenTest.kt`) updated for single-select/auto-dismiss behavior and compiles successfully; not yet re-run on a device or emulator to confirm it passes.
- [ ] Visual emulator verification and live on-device Gemma quality evaluation; no physical-device test or reinstall is authorized by this change.

History has been removed from navigation and app behavior. New suggestions are not automatically recorded. Existing saved wines remain in My List; legacy history storage is not erased.
