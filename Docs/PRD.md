# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Last updated:** 4 October 2026 (Menu, Directory and Wine Production added)
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Chat opens with a Kotlin-owned mode choice — **curious about wines and pairings** or **find a wine** — rather than an open-ended greeting. Choosing "find a wine" drives a fixed, entirely Kotlin-owned Q1 (type) → Q2 (country/province) → Q3 (taste: body, tannin, acidity, sweetness) sequence: each answer is matched deterministically against curated keyword vocabularies (no model call), explicit lack-of-preference answers are valid, and an unrecognized answer either repeats the question with an apology or, when it reads as a genuine tangent or wine question, gets a brief on-device Gemma reply before the pending question is re-asked. Once Q1-Q3 all resolve, Kotlin builds the Kaggle query directly from the recorded preferences and runs it concurrently with a short-lived Gemma card-synthesis call; the cache is queried once Kaggle completes. Every response — hit or miss — is followed by a standing offer to run a broader web search, which only runs if accepted (AC10c). Newly resolved web-search profile data is written to the growing on-device `VarietyRegionProfile` Room table with source `web_search`, after checking it isn't already covered by Kaggle (AC10f). Choosing "curious about wines and pairings" instead hands the conversation to a persistent, open-ended Gemma chat scoped to wine topics; an explicit mid-chat request can switch from curious mode into the Q1-Q3 flow (the reverse direction is not currently implemented).

**Revision, 2–4 October 2026:** Find no longer uses Gemma. Its results come from stored data only (the reviews database, then the Extended db, with an on-demand Brave web search) and are presented in two score tabs. Chat's "find a wine" flow hands its answers to that same Results page. A new **Ask** conversation on each wine's Profile Page, and Chat's open conversation, give Gemma on-demand access to the app's reference data (Section 4, Section 6B). Sections 6, 6A and 6B carry the details; where an older statement below conflicts, the revision sections win.

Find is the default entry point, accepting any combination of Type, Variety, Location, Tannin, Body, and Acidity selections (Sweetness was removed on 4 October 2026). It queries on-device Gemma and the bundled database independently, with no Chat fallback, profile-cache access, web search, or automatic History recording. See Section 6A.

## 2. MVP Scope

Seven screens, plus a Menu sheet on the main page:
1. Splash Screen
2. Find (form and Results page)
3. Main Conversation Screen
4. Profile Page
5. Ask (the AI sommelier conversation opened from a Profile Page — Section 6B)
6. My List
7. Winery Directory (opened from the main page's Menu — Section 6C)

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

`summary` is populated for both **Chat's** and **Find's** Gemma recommendations, but no longer by the initial card-generation call for either source. As of the 27 September 2026 two-stage rework (Section 6, AC3/AC4a and Section 6A, GS-AC10), the initial call (`chat_search_instruction.txt`, shared by Chat and Find) returns only the four compact-card fields (`name`, `country`, `province`, `variety`) and never requests `summary`; a second, on-demand call (`wine_detail_instruction.txt`) fills `summary` — along with `winery`, `wine_type`, `sweetness`, `body`, `tannin`, `acidity`, `flavor_notes`, and `suggested_pairing` — the first time the wine's Profile Page opens, for any Gemma-sourced card not already `profileComplete`. This resolves the previously open item flagged below and in Section 10: Chat cards' `Summary` section is no longer permanently `Unknown` by construction. It contains a few concise descriptive lines about the recommendation and stays below 200 characters. Kaggle and web-search options never populate it.

**Later the same day, the dedicated second-stage "complete the profile" Gemma call that could have backfilled this field on revisit (`loadProfile()`, `profile_system_instruction.txt`) was found to be unreachable dead code** — every live card-creation path (Chat search, Guided Selection) already marked `profileComplete = true` the moment a card was published, so the reload never actually ran — and was deleted outright rather than left in place. See Bug-Log.md #6 and Trade-offs.md for detail. **As of 27 September 2026, that mechanism has effectively been rebuilt, not revived:** a new on-demand detail call (`wine_detail_instruction.txt`, `GemmaConversationResponder.enrichWineDetails()`) now fires from the Profile Page (`StageShowRoute`'s `loadDetails` callback) exactly when a Gemma-sourced card opens with `profileComplete == false`, filling `summary` and the rest of the full profile at that point rather than at card-generation time. Cards from Kaggle, cache, web search, or a saved favorite are already `profileComplete` and never trigger this call.

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

### Reference data sources — names and roles (4 October 2026)

| Name | What it is | Where it lives | Used for |
|---|---|---|---|
| **Reviews database** | About 119,030 critic reviews: wine, winery, country, province, variety, points (80–100), review text, and the precomputed body/tannin/acidity labels | Bundled SQLite `wine_reviews.db`, copied to app storage on first launch | Find's Reviews results; a wine's own facts; the even review sample; wineries that have reviews of a grape; the other-countries offer |
| **Grape_Profile_Internal** | 69 curated grape entries (35 red, 34 white; 65 of the 404 single grapes): summary, body, acidity, tannin, sweetness, alcohol band, aromas, flavours, ageing, origin, key regions. Written in the project's own words; carries no source references | `assets/knowledge/grape_profile_internal.jsonl`, read into memory at startup (copy in `Docs/RAG/`) | The trusted source for a grape's characteristics |
| **Grape_Profile_Kaggle_Extracted** | 4,119 country/province/variety profiles (body, tannin, acidity, flavour notes) extracted from reviewer wording; a trailing `*` means thin evidence; about a quarter have no body/tannin/acidity | `assets/grape_profile_kaggle_extracted.json`, seeded into the Room table `VarietyRegionProfile` on first launch | The fallback when a grape has no internal profile, or for another country; always credited to people ("Wine enthusiasts say…"), never stated as fact |
| **Wineries_Directory** | About 30,450 wineries with country and region (cleaned from 30,589 rows) | `assets/knowledge/wineries_directory.csv`, loaded in the background at app start (original in `Docs/Wineries_Directory.csv`) | A winery's location; unranked samples of wineries in a place |
| **Extended db** | Saved web-search results (the `web_search` rows of `VarietyRegionProfile`); starts empty | Same Room table as the Kaggle profiles, kept apart by the `source` column | Extended db sections in Find and Chat |

**Order of trust for body, tannin and acidity:** Grape_Profile_Internal first; only if the grape has no internal profile, Grape_Profile_Kaggle_Extracted, credited to enthusiasts; only if neither has it, Gemma's own knowledge, flagged as general knowledge.

**Variety list (4 October 2026).** The 701 raw `variety` values reduce to **404 single grapes** once aliases are merged (Syrah/Shiraz, Grenache/Garnacha, Pinot Gris/Pinot Grigio, Zinfandel/Primitivo and others), plus 189 blends, 8 style labels and 2 stray entries. The 404 power Find's Variety picker, ordered by number of reviews. The audit is in `Docs/varieties_merged.csv`.

**Country names.** The saved profiles used "US" and "England" where the reviews database, Find, Chat and the Wineries_Directory use "United States" and "United Kingdom". The seed file was corrected (752 rows) and Room migration 3→4 renames existing rows; Chat's country aliases now resolve to the standard names.

### Country-province-variety profile storage

The app has a growing on-device Room table named `VarietyRegionProfile`, keyed by the combination of `country`, `province`, and `variety`, in that order. Each row stores those key values; nullable body, tannin, and acidity values; a list of flavor notes; generation metadata; a source value of either `kaggle_derived` or `web_search`; and one cached web-search option containing its winery, `web_summary`, and other resolved option fields.

- On first launch, if the table is empty, the app reads `grape_profile_kaggle_extracted.json` from packaged assets and inserts all profiles in one Room batch operation.
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
- **AC4b:** Given the country-province-variety Room table is empty when the app starts, then `grape_profile_kaggle_extracted.json` is parsed from packaged assets and inserted off the main thread. Given the table already contains rows, seeding is skipped without replacing runtime additions.

### Error Handling
- **AC5:** Given the on-device model load fails, when the user taps retry, then the model load is re-attempted.
  - **AC5a:** Given retry fails three consecutive times, then a message advises checking device storage or compatibility. No cloud fallback applies to this check — it is specifically verifying the on-device model.

**Open assumptions:**
- The splash progress bar's minimum paced duration is implemented as a fixed 3.5 seconds (`MINIMUM_PREPARATION_MILLIS`, reduced from an earlier 5 seconds) before it can reach 100%; actual navigation still waits for real model preparation to finish if that takes longer. A separate "taking longer than usual" message for a slow load beyond this pacing is not yet defined.
- On-device path is settled on the pinned `gemma-4-E2B-it.litertlm` artifact and LiteRT-LM runtime. Download progress is byte-based; local checking and SHA-256 verification are indeterminate phases.

---

## 6. Main Conversation Screen

**Purpose:** Single chat-style interface, casual and personable in tone. The deterministic Q1-Q3 flow targets three option cards once it completes and requires at least one. Gemma's card synthesis and the Kaggle database query run concurrently the moment Q1-Q3 closes, using Kotlin's own recorded preferences rather than waiting on Gemma's output; the cache is queried once Kaggle completes, and the web-search layer runs only if offered and accepted. The detailed profile loads on the Profile Page.

### Revision — 4 October 2026 (supersedes the older statements below where they conflict)

- **Q3 no longer asks about sweetness.** It lists Body, Tannin and Acidity only; a stray "sweet" or "bone-dry" in an answer is not read as a choice. Chat's Q1 type "sweet" still exists and becomes a Sweet filter when handed to the Results page.
- **Hand-off to the shared Results page.** When Q1-Q3 resolve, Chat converts the recorded answers into the same selections as the Find form (`WinePreferences.toGuidedCriteria()`) and opens Find's Results page. No Gemma card synthesis runs for Chat results; the chat's own multi-source path remains only as a fallback when the answers cannot be converted.
- **Open conversation ("curious about wines") has lookups.** It uses the same on-demand extras as Ask (Section 6B): grape profiles, Kaggle-extracted style (credited to enthusiasts), an even review sample (the grape and country come from the question; if either is missing Gemma is told to ask), the other-countries offer, Wineries_Directory entries, and wineries that have reviews of a named grape. `curious_chat_instruction.txt` gained a Lookups section with the order of trust. Pairings stay out of scope.

### Navigation & Display
- **AC1:** Given the app has passed splash, when the Main Conversation Screen loads, then a persistent text input is displayed at the bottom of the screen.
  - **AC1a:** Given a new conversation opens, then UnCork briefly introduces itself as the user's personal sommelier and asks a fixed, Kotlin-owned mode choice: **curious about wines and pairings** or **find a wine**, offered as both tappable quick-reply buttons and matchable free text. This replaces the earlier design where the greeting asked directly about wine type.
  - **AC1b:** Given the launch greeting or any fixed Q1-Q3 question is already visible, then it is never regenerated by a model — this and every onboarding question in AC3 are fixed Kotlin copy, not Gemma output, so there is no risk of the app "introducing itself again."
- **AC2:** Given a prior response exists in the current session, when the screen renders, then the conversation thread displays above the input, most recent at the bottom.
  - **AC2a:** Given the app is closed and reopened (a new session), when the Main Conversation Screen loads, then the screen starts fresh — the previous session's suggestions are not shown inline, and only explicitly saved wines remain available in My List.
  - **AC2b:** Given the LLM can produce any number of suggestions within one session, when the user's query shifts to a different topic within the same session, then earlier suggestion cards remain visible in the thread rather than being cleared or collapsed.

### Content Tone
- **AC3:** Given the deterministic Q1-Q3 flow closes (all three questions resolved), then a short-lived Gemma call returns exactly three distinct card records as its own source section. As of the 27 September 2026 two-stage rework, each record is a **compact identity payload the first time it's produced** — `name`, `country`, `province`, `variety` only (`chat_search_instruction.txt`) — with the remaining fields (`winery`, `wine_type`, `sweetness`, `body`, `tannin`, `acidity`, `flavor_notes`, `summary`, `suggested_pairing`) generated by a separate on-demand call the first time the card's Profile Page opens (see AC4a, and the Section 4 schema note). This supersedes the same day's earlier, shorter-lived "ask for the full profile up front" design (25 September progressive-card rework) once on-device timing showed the two-stage split gave a larger latency win — see the Performance rework note below. Gemma must choose options for which both name and country are known; neither mandatory field may be `Unknown`. Variety and province may be `Unknown`.
- **AC3-progressive:** Cards are parsed and published incrementally as Gemma's response streams in, not only once the full response finishes — the moment a card's JSON object closes (`CompleteJsonObjects`, tracking brace depth and string/escape state so a stray `{`/`}` inside a quoted value doesn't miscount), it's validated against the recorded `WinePreferences` (AC3k) and, if it passes, immediately published to the Gemma block's card list while the block's status stays `LOADING` until all three (or the stream ends) resolve. A card opened from this partial, still-loading list shows its known fields immediately and its still-missing fields as individual per-field loading spinners (see AC4a) rather than blocking the whole page. Each initial-search request writes one metadata-only timing record (`GemmaTimingTrace`, debug builds only — no prompts, preferences, or generated text) to `files/diagnostics/gemma-timings.log`, documented in `Docs/Gemma-Timing-Diagnostics.md`; the separate on-demand detail call writes its own record (`StageShowTimingTrace`, same debug-only/metadata-only rules) to `files/diagnostics/stage-show-timings.log`. The curious-chat conversation path also records a debug-only `user_send_to_first_token` timing (measured from the moment the user sends a message to the first streamed Gemma text chunk), tagged `operation=chat_conversation`, in the same `GemmaTimingTrace` mechanism.
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
- **AC3d-curious:** Given the user instead chose "curious about wines and pairings" at the mode choice, then the conversation hands off to a persistent, multi-turn Gemma chat scoped to wine topics via a dedicated system instruction (`curious_chat_instruction.txt`). A narrow runtime filter avoids repeating a previously used opening filler phrase within the same session. An explicit mid-chat request to find a wine (e.g. "let's find a wine," matched by `requestsFindWineSwitch` against a fixed phrase list — see AC3o) switches into the Q1-Q3 flow starting at Q1, discarding any prior curious-mode context. As of 27 September 2026, the prompt also instructs Gemma that when the user expresses intent to make a selection, it should say it needs a few details first and ask the user to confirm before handing back to the Q1-Q3 flow; this is prompt-level guidance for Gemma's own reply text, not a separate Kotlin-enforced confirmation step — the underlying mode switch still fires deterministically off `requestsFindWineSwitch`, independent of whether Gemma's own reply asked for confirmation.
- **AC3o — mode switch:** Given the user is inside the Q1-Q3 flow, an explicit request to switch modes is not currently handled by a dedicated matcher the way the curious→find-wine switch is; only curious-mode → find-wine mid-chat switching (AC3d-curious) is implemented today.

Field-coverage tracking as previously specified (hidden `[FIELD_COVERAGE]`/`[STATE_SNAPSHOT]` markers, per-turn Gemma-classified `clarify`/`closed` state) has been retired along with the Gemma-led onboarding it supported. The equivalent behavior — knowing when Q1, Q2, and Q3 have each resolved — is now plain Kotlin state (`FindWineStep`), decided without a model call, per AC3h and AC3i above.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC4a:** Given Gemma is asked for recommendations in Chat or Find, then its initial-search output contains only the compact card fields (`name`, `country`, `province`, `variety` — AC3/AC3-progressive/GS-AC7); it does not generate winery, rating, review information, `body`/`tannin`/`acidity`/`sweetness`/`flavor_notes`/`suggested_pairing`/`summary` at this stage, and any of those fields the user already supplied through Q1-Q3 or Find's selections are merged in from the recorded preferences (`WinePreferences.mergeIntoGemmaCard`), which take priority over anything Gemma would otherwise generate for that field. A resulting card starts with `profileComplete = false`. When its Profile Page (`StageShowRoute`) opens for the first time, a second on-demand Gemma call (`wine_detail_instruction.txt`, `enrichWineDetails()`) fills every field still missing — `winery`, `wine_type`, `sweetness`, `body`, `tannin`, `acidity`, `flavor_notes`, `summary`, `suggested_pairing` — while each still-pending field on the page shows its own small loading indicator rather than blocking the rest of the page; the card is then marked `profileComplete = true` so reopening it never re-fires the call. This on-demand call is skipped entirely for cards from Kaggle, cache, web search, or an existing favorite, since those are already `profileComplete` at creation. Any value the second-stage model returns as empty, `null`, or the literal string `"None"` is normalized to `"Unknown"` rather than shown or stored as-is (`knownString`/`stringList`). The on-demand detail request remains bounded to 45 seconds; if it doesn't return in time, the page falls back to `Unknown` for the still-unresolved fields rather than leaving a loading indicator on screen indefinitely. `suggested_pairing` remains `Unknown` unless the original request explicitly asked for a food or cheese pairing. Gemma never produces `winery`, `rating`, `review_summary`, `web_summary`, or `confidence`.
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
  - **AC10j (25 September 2026):** As a display-level backstop on top of the existing parser-side name requirement, both Chat's and Find's result lists filter out any card whose `name` is blank or literally `"Unknown"` immediately before rendering, rather than showing a nameless card. Added after observing Gemma occasionally produce a card with no usable name despite passing upstream validation (see Bug-Log.md #7).
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

### Performance rework — 27 September 2026 (on-demand detail loading, superseding the same-day full-profile-up-front design)

`Docs/Gemma-Performance-Optimization-Summary-2026-09-25.md` documents a further round of the same investigation: asking Gemma to write the full per-card profile up front (the design AC3 described immediately above) still meant generating three full profiles' worth of text even when the user only opens one card. Splitting generation into the compact-card stage (`chat_search_instruction.txt`: `name`/`country`/`province`/`variety` only) plus an on-demand detail stage (`wine_detail_instruction.txt`, fired only when a card's Profile Page opens) took the measured on-device workflow from a historical 48.13s average down to 6.28s for all three initial cards (first card 2.96s, second 4.32s, third 6.19s), with the selected card's on-demand detail fill separately measured at 8.68s. **Find/Guided Selection was moved onto this same shared pipeline the same day** (`guidedSelection()` now calls the same `synthesizeCards`/`enrichWineDetails` path Chat uses, rather than its own dedicated prompt) — see GS-AC7 and GS-AC10 in Section 6A. `guided_instruction.txt` is left on disk but is no longer referenced by any code path as a result; it is a cleanup candidate, not a documented behavior, alongside the other superseded/dead paths already noted in this section and in Section 10. As with the prior benchmark documents, these are measured baselines from uncontrolled single runs, not thresholds enforced by any test.

### Trade-offs

- **Compound Q1-Q3 answer matching (AC3n-1)** uses a symmetric scope — every reply is checked against every not-yet-resolved later question — chosen deliberately over a narrower "next question only" scope despite a higher false-positive surface (an incidental word in a Type/Country answer can silently resolve Taste). Narrowing this later would need explicit re-scoping, not just a bug fix.
- **`us` and bare `no` are excluded from the reply-phrase and country-alias matchers** (AC3d, AC10i) despite being short, common ways to answer, because both collide with ordinary English words/negations in ways that would misfire on unrelated replies. The unambiguous longer forms (`usa`, `america`, `american`, `no preference`, `nope`, `nah`) remain covered; this is a deliberate coverage/safety trade-off, not an oversight.
- **The 45-second Profile Page on-demand detail-load timeout (AC4a)** bounds how long a per-field loading indicator can show, but a genuinely slow (not hung) device could still hit it and fall back to `Unknown` for a field (including `summary`) that would have arrived a few seconds later. No retry action is offered for this specific timeout today.
- **The web-search retry action (AC6f, AC10i) reuses the existing turn-based affirmative-reply pathway** ("Try Again" is sent as an ordinary chat message matched against the same phrase list) rather than a dedicated retry state machine — simpler to reason about and test, at the cost of the retry being one more conversational turn rather than an in-place re-fetch.
- **Splitting card generation into a compact initial call plus an on-demand detail call (27 September 2026 rework, AC3/AC4a) means a card's full profile — including `summary` — is genuinely unknown until its Profile Page is opened at least once.** This was an open product decision as of 25 September (see the removed trade-off item this replaces); it's now resolved as a two-stage design rather than by leaving the field permanently empty, at the cost of a second on-device model call (bounded to the 45-second timeout above) the first time each card opens.

**Open assumptions:**
- Exact routing heuristic for cloud escalation (word count? explicit constraint count?) is not yet defined — needed before this can be built.
- Assumed cheese pairing is requested via a dedicated action, not by re-parsing free text for intent — confirm this matches your expectation.
- Exact styling and visual treatment of the wine option-card section within the chat response.

---

## 6A. Find

### Revision — 3–4 October 2026 (supersedes the items marked "Superseded" below)

- **Header and layout.** The form opens with the H2 "Enter a Spec" and "Choose your preference"; section titles ("Wine Style & Origin", "Taste Profile") are H3. The filter-count pill has no background. **Sweetness was removed.** Taste Profile is Body | Tannin, then Acidity.
- **Variety filter.** A new single-select Variety tile under Type lists the 404 single grapes, most common first. A selection matches every merged database spelling at once (`GrapeVarieties`, `GuidedReviewQuery`), counts as a filter, and appears in the tags and the web-search query. Type and Variety can contradict each other (for example Red and Chardonnay) and then return nothing.
- **Gemma removed from Find.** The AI Sommelier section, its loading placeholders, retry and "Model setup" button are gone; Find shows stored data only. AI Sommelier is for Chat and Ask.
- **Results page.** Tags are plain text separated by " | " with a right-aligned **Web Search** button in the same row. Below: score tabs **91–100 pts** (default) and **80–90 pts**, one Sort menu (Top ranked, By Country, By Variety — it reorders the loaded rows), a pulsing skeleton while loading, ten wines per tab with a **More** button (offset paging, one extra row fetched to detect more), then **Extended db**, then Web Search results only after the button is tapped. Section order everywhere is AI Sommelier (Chat), Reviews, Extended db, Web Search; "Reviewed Wines" is now "Reviews" and "Cache db" is "Extended db".
- **Unbiased ranking.** Results rank by points; wines with equal points are ordered by a seeded hash of the id, with a fresh seed per search and stable across paging, instead of alphabetically by winery.
- **Web Search.** Runs Brave on demand for the submitted selections; failure or a missing key shows "Web Search could not be completed" with Try Again. Find's web results are not yet saved to the Extended db (only Chat's are).
- **Extended db in Find.** Runs automatically with each search against the saved web results, matching type, country, province, variety, body, tannin and acidity. It shows "No results found" when a sweetness filter is present (reachable only via Chat's "sweet" type), because saved results carry no sweetness data.


**Current design:** Find is the default tab after splash. Tab order: **Find, Chat, My List**. This supersedes the earlier Guided Selection layout, required variety/country/region choices, and ten-country shortlist.

### Layout and selection

The Find layout follows the supplied visual reference: parchment background, burgundy accents, a filter-count pill and Clear all action, followed by Type, Location, and Taste profile sections separated by thin rules. Search stays visible above the existing three-tab navigation while content scrolls. A helper link opens Chat.

| Category | Control and options |
|---|---|
| **Type** | A tile that opens a bottom sheet listing Red, White, Sparkling, Rosé, Fortified as a single-select vertical list, plus an "Any type" row. Tapping a value applies it and closes the sheet immediately — there is no "select any that apply" multi-select mode and no Done button. Sweet is a Sweetness option rather than a Type. |
| **Country** and **Province** | Two independent tiles side by side, each opening its own single-select bottom sheet (not one shared "Location" sheet). Each list is alphabetically sorted from the bundled database, with an "Any country"/"Any province" row at the top; Province narrows to the selected Country. Selecting a value applies it and closes that field's sheet immediately. Changing Country clears Province. |
| **Taste profile** | Bold section heading with “Optional — leave blank if you're not sure.” |
| **Sweetness** | *(Removed 4 October 2026.)* Previously Bone-Dry, Off-Dry, Sweet. |
| **Tannin** | Smooth, Moderate, Astringent, single-select vertical list with an "Any tannin" row. |
| **Body** | Light-Bodied, Medium-Bodied, Full-Bodied (displayed without the "-Bodied" suffix), single-select vertical list with an "Any body" row. |
| **Acidity** | Soft, Crisp, Tart, single-select vertical list with an "Any acidity" row. |

Every field on the Find form — Type, Country, Province, Sweetness, Tannin, Body, and Acidity — is single-select: choosing a value replaces whatever was selected before rather than adding to it, and immediately closes the bottom sheet. There is no Done button on any field's sheet. Each option row is a horizontal-divider-separated single-select radio row (a 20dp indicator circle plus label, `bodyMedium` typography, a 48dp minimum tap target), identical in structure and sizing across every field's sheet so height, font, and spacing cannot drift between fields. Accessibility exposes each row as a radio-button control. This is a change from the earlier multi-select, three-column checkbox-grid design: every field previously supported selecting several values at once per category (OR within a category); this is retired in favor of one value per field.

Every category is optional. Start with no selections. Tapping the "Any {field}" row clears that field. Clear all resets all selections and the count to zero, disabling Search; it does not silently re-run or overwrite prior results. Count each selected option plus one for an active Country or Province selection. Country and Province remain single selections; clearing/changing Country clears Province. Persist and display the field as Province, with `USA` displaying the stored country value `US`.

Option labels come directly from the shared evidence-map keys in `FindPhraseEvidence`. Gemma uses those same descriptive values; the app's shared profile schema remains string-based, preserving existing values and ranges.

The location list is loaded directly from the bundled database, currently 43 countries and 425 distinct provinces. It is not limited to the earlier curated list. If loading fails, provide Retry locations; users can still search with Type or tasting preferences.

### Search and source behavior

- **GS-AC1:** Find opens by default after splash and displays the sections and controls described above. *(Superseded 4 October 2026: a Variety selector now appears and Sweetness is gone — see the Revision above.)*
- **GS-AC2:** Tapping the Country tile opens the alphabetically sorted country bottom sheet directly (there is no separate combined "Location" sheet). Selecting a country applies it and closes the sheet.
- **GS-AC3:** Tapping the Province tile opens an alphabetically sorted province sheet, narrowed by Country when selected. Selecting a province applies it and closes the sheet. Changing Country clears Province.
- **GS-AC4:** Unselected fields mean no preference and are excluded from both sources' constraints. They must not become hidden default filters.
- **GS-AC5:** Any single selection enables Search, including Type alone, Country alone, Province alone, or any one tasting preference. An entirely empty selection disables Search.
- **GS-AC6:** *(Superseded 4 October 2026: Find no longer starts a Gemma search; it starts the two score-tab Reviews queries and the Extended db lookup.)* Search captures an immutable snapshot and starts on-device Gemma and Database searches independently. Both receive the same selected criteria. One source's completion, failure, or cancellation does not wait for or cancel the other. As of 27 September 2026, Gemma's results stream into the Find results list incrementally as each card resolves (`GuidedResult.Loading(cards)` carries the partial list) rather than the section staying opaque until all results are ready — the same progressive-publish behavior Chat already had (AC3-progressive).
- **GS-AC7:** *(Superseded 4 October 2026: Find has no Gemma recommendations.)* Gemma names one real, known wine per recommendation, consistent with all selected criteria. As of 27 September 2026, Find's Gemma search (`guidedSelection()`) runs on the same two-stage pipeline Chat uses (Section 6, AC3/AC4a) rather than its own dedicated prompt: the initial call uses `chat_search_instruction.txt` and returns only `name`/`country`/`province`/`variety` for up to three genuinely different, real, known wines; any attribute the user already selected on the Find form is merged in from the recorded `GuidedCriteria`/`WinePreferences` rather than requested from Gemma; the remaining profile fields (including `summary`) are generated on demand by `wine_detail_instruction.txt` the first time the card's Profile Page opens (see GS-AC10). The Find-specific `guided_instruction.txt` prompt asset is no longer referenced by any code path as a result of this change — a cleanup candidate, not documented behavior. `GuidedCriteria`'s fields were migrated from `Set<String>` to plain `String` on 25 September 2026 (see Trade-offs.md, item 8, superseded): the type itself now enforces single-select instead of relying on UI convention over a structurally multi-value type, and the now-unreachable `toggled()` multi-select helper was deleted. Validate every selected field, including Type, before displaying the recommendation. Reject contradictory/malformed output with a retryable error — including the model echoing the JSON schema's own field name back as a value, or hallucinating a markdown/URL citation in place of a name (see Bug-Log.md #7) — a valid empty recommendation array is an empty result. Do not invent a winery, critic score, or review.
- **GS-AC8:** *(Partly superseded 4 October 2026: results are limited to a score tab, ordered by points then a seeded hash, ten per page; the Variety filter matches all merged spellings.)* Database combines selected categories with AND. Each category is now a single selected value (the query builder's `classified()` helper does a plain equality match; the `IN (...)`-based OR-within-a-category union it previously built for a multi-value category was removed as unreachable once `GuidedCriteria` moved to single `String` fields — see GS-AC7). Selected Country and Province use case-insensitive equality. Type uses case-insensitive equality against the exact mapped `variety` values for the one selected type. Never infer Type from a wine name or review. An empty configured mapping returns no Database results without broadening the search. Tasting preferences use review-text phrase evidence. Return at most three matches, ranked by points descending, winery ascending, then ID ascending; null points always sort last. No keyword broadening or fallback runs.
- **GS-AC9:** Before searching, show guidance. After submission, Gemma and Database each render their own loading, results, or empty state as soon as ready. Both Find's and Chat's result lists share the same card layout component (`WineResultCard`) as of 27 September 2026, and Find's own result section (`GuidedSourceSection`, formerly a bespoke `ResultSection`) reuses Chat's `SourceResultCard`/`SourceQueryStatus` components rather than duplicating loading-indicator and card-list rendering.
- **GS-AC10:** Cards open the existing Profile Page with all source fields preserved. As of 27 September 2026, a Gemma-sourced Find card is not yet `profileComplete` when it first appears — the Profile Page fires the same on-demand detail call described in Section 6, AC4a, and `summary` (along with the rest of the full profile) resolves at that point, with a per-field loading indicator shown for anything still pending. Database supplies the full Critic Review and nullable critic score; a Database card is already `profileComplete` and never triggers the on-demand call. Unavailable Database attributes remain Unknown, rather than copying search preferences as facts. No additional inference, profile-cache access, or web lookup runs beyond the one on-demand Gemma detail call above, including from My List. Explicit Save remains available.
- **GS-AC11:** Each empty source independently shows “No matches for these selections.”
- **GS-AC12:** *(Superseded 4 October 2026: no Gemma in Find.)* A Gemma failure provides Gemma-only Retry using the submitted criteria and a route to model setup. No cloud escalation occurs.
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

*(Superseded 4 October 2026 — see the Revision.)* On-device Gemma only; at most one Gemma card and three Database cards. No cloud/web fallback, `VarietyRegionProfile` access, or automatic History entries. Explicit Save uses My List. These rules are independent of Chat's fallback pipeline.


## 6B. Ask — the AI sommelier on a wine

**Purpose:** An open conversation about one wine, or about wine in general, opened from the Profile Page. It is not the Find-a-wine chat: no mode choice, no Q1-Q3.

### Behaviour
- **ASK-AC1:** Tapping **Ask** on the Profile Page opens the Ask screen (title "Ask") immediately with a welcome: "Hi! I'm Uncork, your AI sommelier. I can help you with questions about **{wine name}**." followed by a burgundy bold "I can help you with:" and the topics Grape Varieties, Flavours and aromas, Wineries, Consumer reviews, Wine production. Back returns to the wine's Profile Page. Food and cheese pairings are out of scope for now, as in Open-Chat.
- **ASK-AC2:** The wine's facts are gathered in the background while the welcome shows (the screen is never held up by lookups), and Gemma reads them before the user types (warm-up; the reply is discarded). The first message carries: the wine's own facts (source, name, winery, place, variety, score, body/tannin/acidity, flavour notes, critic review), its **Wineries_Directory** location when listed, its **Grape_Profile_Internal** entry (or, only when the grape has none, **Grape_Profile_Kaggle_Extracted** for the wine's country, credited to enthusiasts; `*` is shown as "limited evidence"), and an OTHER COUNTRIES line (top-reviewed countries for the grape) to offer.
- **ASK-AC3:** Added in front of a question only when it needs them (`<more_context>`, each once per conversation, resent after a rebuild): the broader review sample when the user asks what people, critics or reviewers think; the Internal entry for another grape the user names; Kaggle-extracted style for a grape with no internal entry or for another country the user names; the other-countries offer for a named grape; Wineries_Directory entries for a named winery or an unranked sample of wineries in a named place; and a sample of wineries that have reviews of a named grape (never "the best"). A short reminder of the wine and the honesty rule precedes every follow-up.
- **ASK-AC4:** The review sample is even-handed: equal numbers (12) from the highest-scored, middle and lowest-scored thirds of the reviews of that grape (all spellings) from that country, seeded so the same wine gives the same sample, with 140-character excerpts and province labels; "Other" province buckets and unscored reviews are excluded.
- **ASK-AC5:** Order of trust for body, tannin and acidity: Grape_Profile_Internal; then Grape_Profile_Kaggle_Extracted credited to people ("Wine enthusiasts say…", "What consumers say…", "Reviewers often describe it as…"); then Gemma's own knowledge flagged as general knowledge. Gemma stays with the wine's country (or the one the user names) and offers other top-reviewed countries. Gemma never states bottle specifics beyond the facts (score, vintage, price, alcohol), never calls a wine or winery real or fake, and never recommends other wines or producers (unranked examples from a provided list are allowed).
- **ASK-AC6:** `wine_discussion_instruction.txt` holds the rules: excited but natural tone, 2–4 plain sentences, the Open-Chat honesty/scope/safety guardrails, and varied wording for general-knowledge phrases.
- **ASK-AC7:** The engine is configured with an 8,192-token window (`ENGINE_MAX_TOKENS`) after a smaller default caused cut-off and empty replies; an empty reply triggers one conversation rebuild and retry; the app logs a running estimate of tokens used (the runtime reports none). Changing the window forces a one-time slow GPU re-preparation (about 45 s on the test phone).

### Notes
- The first message is roughly 1,000–1,500 tokens; the rules about 1,300. Gemma closely reads only about the last 512 tokens in its local layers, which is why the per-question reminder exists.
- Not yet verified on a physical device beyond manual spot checks of earlier builds.

## 6C. Menu and Winery Directory

**Purpose:** A reference corner reached from the main page. It does not change Find, Chat or Ask.

### Menu
- **MENU-AC1:** Tapping **Menu** in the main page's bottom bar opens a bottom sheet titled "Menu" with three rows, each with a trailing chevron: **About Uncork**, **Winery Directory**, **Wine Production**. Tapping outside or swiping dismisses the sheet without navigating.
- **MENU-AC2:** **Winery Directory** opens the directory (below). **Wine Production** opens the production-facts browser (below). **About Uncork** currently opens a placeholder page ("… is coming soon") with the standard header and the Back/Home bottom bar; its content is not yet written.
- **MENU-AC2a (Wine Production):** One page per layer under the title **Wine Production**, with the same bold breadcrumb line as the Directory above the list: **Select Country**, then **France > Select Variety**, then **France > Merlot > Select Region - Wine Area**, then **France > Merlot > Bordeaux** above the production steps. The breadcrumb is plain text. Country page lists France first, then Spain and United States (sample); only France has a chevron. All list rows are the same height. Drill-down: France → grape → place → production steps, from `Docs/FRENCH_WINE_KNOWLEDGE_SEED.md` (asset `french_wine_production.json`). List rows (country, variety, region) look like the Directory's: black, 17 sp, chevron on the ones that open; production step headings are burgundy. A place records only what differs from the place above it; other steps show as "Inherited from {place}". Grapes with no recorded facts (all but Merlot and Chardonnay) say so rather than showing invented text.

### Winery Directory
Browses the Wineries_Directory (Section 4): about 30,450 wineries across 44 countries, grouped by region as the data records them. The data is bundled, read in the background when the app starts, and shown with a spinner (or "could not be loaded") only if it is not ready.

- **WD-AC1:** **Country page.** An alphabetised list of countries, each with its winery count and a chevron. Header title **Directory**; a breadcrumb line on the page above the list reads **Select Country**.
- **WD-AC2:** **Regions page.** Selecting a country opens a new page listing that country's regions/provinces, alphabetised, each with its winery count and a chevron. Header title **Directory**; the breadcrumb above the list reads **{Country} > Select Region**.
- **WD-AC3:** **Wineries page.** Selecting a region opens a new page listing that region's winery names, alphabetised and de-duplicated (no further drill-down). Header title **Directory**; the breadcrumb above the list reads **{Country} > {Region} > Winery**.
- **WD-AC4:** The breadcrumb is 14 sp, bold, in the app's ink (black), and is not part of the page title. Titles over 16 characters step down from 24 sp to 18 sp and may wrap to two lines.
- **WD-AC5:** Sorting ignores case and accents (a person's reading order: "Añelo" sorts with "An…").
- **WD-AC6:** The bottom bar keeps the standard **Back** and **Home** buttons. Back (and the system back gesture) goes up one level and leaves the directory from the country list; Home returns to the main page from any level.
- **WD-AC7:** The directory is read-only. Winery rows are not tappable.

### Notes
- The shared header (`AppHeader`) gained an optional subtitle; other screens are unchanged.
- Tests: `WineryDirectoryBrowseTest` (alphabetical order, counts, headers) passes; `WineryDirectoryScreenTest` (drill-down and back) compiles but has not been run on a device.

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
- **AC4b:** A **Database** Find profile is complete when its card appears and does not trigger additional model inference, cache access, or web searches, including when reopened from My List. A **Gemma** Find profile, as of 27 September 2026, is not complete when its card first appears (see Section 6A, GS-AC10 and Section 6, AC4a) — opening its Profile Page for the first time fires one on-demand Gemma detail call, after which it behaves like any other complete profile on every later reopen, including from My List. Given the entry came from Gemma's own suggestion without a matched option, when its narrative field resolves, then its under-200-character descriptive text is displayed in a narrative section labeled `Summary`. `Critic Review` and `Web Summary` are not displayed, and rating remains absent because Gemma does not supply it.
- **AC4c:** Given the entry came from a cached or live web-search option under Section 6, AC10e, when rendered, then winery, variety, country, province, web summary, and other attributes display where the search resolved them. The narrative section is labeled `Web Summary`; `Summary` and `Critic Review` are not displayed. Rating remains absent because it is reserved for a real Kaggle match.
- **AC4d:** Given `body`, `tannin`, or `acidity` contains the internal trailing `*` thin-evidence marker, whether from fewer than three supporting Kaggle reviews or a web-derived synthesis, when the Profile Page renders that value, then it displays the plain-language annotation `Insufficient data` near the value using the same treatment for either source. The raw `*` character is not shown to the user. The exact caption, tooltip, or icon treatment remains to be defined.
- **AC5:** Given a shared attribute value is `Unknown`, when displayed, then it renders in muted or secondary text, visually distinct from resolved values.
- **AC7:** Given a Kaggle option's winery has been separately verified through a winery-verification web search, when the Profile Page renders, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
  - **AC7b:** The winery-verification search checks one specific Kaggle winery. It is separate from the category-data web search in Section 6, AC10c/AC10e, which the user opts into and which resolves missing attributes. The implementation must keep these as distinct search paths.
- **AC8:** Given the wine has a cheese pairing attached, when the Profile Page renders, then the pairing displays as a secondary section below the wine details, not as the primary focus.

### Actions
- **AC9-Ask (4 October 2026):** The bottom bar's centre action is **Ask** (content description "Ask AI Sommelier"), which opens Section 6B's conversation for the displayed wine. This replaces the former pairing action area described below where they conflict.
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

Status checked against the working code on 27 September 2026, including the Chat recommendation-flow rework and per-source master-card UI redesign described in Sections 4 and 6 (PR #17 and PR #18), the progressive card-streaming/timing-diagnostics performance rework, the card-consistency/Gemma-hardening/`GuidedCriteria` cleanup described below (PR #19, all three merged to `Master`), and two further changes merged 25-27 September 2026: the on-demand detail-loading rework shared by Chat and Find (commits "Load Gemma wine details on demand" and "Stream Find results before loading wine details" — see Sections 4, 6, and 6A) and a small conversation-quality/diagnostics pass (the curious-chat handback confirmation line in AC3d-curious, and the `user_send_to_first_token` conversation-latency timing in AC3-progressive). A checked item is implemented and has passed the computer-only build or automated checks. Partially complete items have working foundations but still require the work stated beside them.

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
- [x] `grape_profile_kaggle_extracted.json` is bundled and seeds all 4,119 profiles off the main thread when Room is empty
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

### Card-consistency, Gemma-hardening, and `GuidedCriteria` cleanup — 25 September 2026 (PR #19)

- [x] The dead second-stage "complete the profile" Gemma call (`loadProfile()`, `profile_system_instruction.txt`) and its plumbing (`MainActivity.kt`, `StageShowScreen.kt`) are deleted outright — every live card-creation path already marks `profileComplete = true` on publish, so the reload had become unreachable; only pre-existing local favorites saved before that flag existed would ever have triggered it, and those now simply show `Unknown` for a missing summary rather than firing a stale reload (see Bug-Log.md #6)
- [x] `chat_search_instruction.txt` and `guided_instruction.txt` field naming unified (`wine_type` everywhere, was `type` in chat search); both prompts now require a real, known wine `name` "from your knowledge" rather than Guided Selection describing a plausible category with no named bottle
- [x] Card display (Chat's `SuggestionLink`, Find's Guided Selection result card) shows a consistent Name → Variety → Winery (only when resolved) → Country, Province layout; winery was added to both prompts and both card layouts, then removed again from all four places per follow-up product direction, leaving only Chat's and Kaggle's independently-sourced winery values where they already existed
- [x] Cards with a blank or literal `"Unknown"` name are filtered out of both Chat's and Find's result lists immediately before rendering (AC10j), on top of the existing parser-side name requirement
- [x] `GuidedGemmaResponse`'s card-dedup key now includes `name` (`GemmaConversationResponder.distinctSuggestions()`, `KaggleConversationResponder.cardKey()`, and `FavoritesRepository.favoriteKey` already did) — previously keyed only on `variety`/`province`/`wineType`, which silently collapsed two genuinely different named wines sharing those three fields down to one card once Guided Selection started asking Gemma for real names
- [x] Gemma output parsing hardened against two observed small-model failure modes: a stray trailing punctuation mark left over from the JSON schema's own formatting (`knownString`/`field` now `trimEnd(':', ';', ',')`), and the model echoing the schema's own field name back as a value (`"name."`) or hallucinating a markdown-link/URL citation (`[75](https://en.wikipedia.org/...)`) in place of a real wine name — both now cause the card to be rejected rather than displayed (see Bug-Log.md #7)
- [x] `guided_instruction.txt` corrected: it previously said fixed criteria are matched "OR within a field, AND across fields," describing a multi-select capability the Find form has never actually exposed (every field has always been single-select — see GS-AC7)
- [x] `GuidedCriteria` migrated end to end from `Set<String>` fields to plain `String` (state, `GuidedReviewQuery`'s SQL builder, `GuidedWineTypeFilter`, `GuidedReviewMatch`, the Guided Selection UI, and Gemma's constraint validation in `GuidedGemmaResponse`), and the unused `toggled()` multi-select helper deleted — see Trade-offs.md item 8 (superseded) for why this was deferred earlier and what changed
- [x] Gemma latency instrumentation (`DebugLatencyLog`: time-to-first-word, first-word-to-last-word, total) added to `guidedSelection()` (Find) and `synthesizeWebResults()` (web-search synthesis), which previously had no timing coverage at all — now consistent with the existing Chat card-search/curious-chat instrumentation

### On-demand detail loading and shared Find/Chat pipeline — 25-27 September 2026

- [x] Chat's card-generation call now returns only the compact `name`/`country`/`province`/`variety` payload; a second, on-demand Gemma call (`wine_detail_instruction.txt`, `GemmaConversationResponder.enrichWineDetails()`) fills `winery`, `wine_type`, `sweetness`, `body`, `tannin`, `acidity`, `flavor_notes`, `summary`, and `suggested_pairing` the first time that card's Profile Page opens, resolving the previously open "Chat cards' Summary is permanently Unknown" product decision without reviving the deleted stage-two mechanism from Bug-Log.md #6 (Section 4, Section 6 AC3/AC3-progressive/AC4a)
- [x] `WinePreferences.mergeIntoGemmaCard()` makes the user's own recorded Q1-Q3/Find selections authoritative over anything Gemma would otherwise generate for the same field, applied at card-acceptance time before a card is ever shown
- [x] `knownString()`/`stringList()` normalize an empty, `null`, or literal `"None"` model value to `"Unknown"` rather than storing or displaying it as-is, on both the initial and on-demand-detail responses
- [x] Find's Gemma search (`guidedSelection()`) moved onto the same two-stage pipeline as Chat (`chat_search_instruction.txt` + on-demand `wine_detail_instruction.txt` via the shared `synthesizeCards`/`enrichWineDetails` path) instead of its own dedicated prompt; `guided_instruction.txt` remains on disk but is no longer referenced by any code path as a result — a cleanup candidate, not documented behavior (Section 6A, GS-AC7)
- [x] Find's results stream in progressively as each Gemma card resolves (`GuidedResult.Loading(cards)` carries the partial list), matching Chat's existing progressive-publish behavior (GS-AC6)
- [x] Find and Chat share one result-card layout (`WineResultCard`) and one source-block/loading-indicator implementation (`GuidedSourceSection` reusing Chat's `SourceResultCard`/`SourceQueryStatus`), replacing Find's previously separate, bespoke result-card and loading-indicator code (GS-AC9)
- [x] A second, separate debug-only/metadata-only timing mechanism (`StageShowTimingTrace`, writing to `files/diagnostics/stage-show-timings.log`) instruments the on-demand detail call independently of the existing initial-search `GemmaTimingTrace`
- [x] On-device measurement of this rework showed the initial three-card search falling from a historical 48.13s average to 6.28s, with the on-demand detail fill for an opened card separately measured at 8.68s (`Docs/Gemma-Performance-Optimization-Summary-2026-09-25.md`) — a single-run, uncontrolled measurement, not a test-enforced threshold
- [x] The curious-chat system instruction (`curious_chat_instruction.txt`) now tells Gemma that when the user signals intent to make a selection, it should say it needs a few details first and ask for confirmation before handing back to the Q1-Q3 flow; this is prompt-level guidance for Gemma's reply text only — the actual mode switch remains driven deterministically by `requestsFindWineSwitch` regardless of how Gemma's reply is worded (AC3d-curious)
- [x] Conversation replies now record a debug-only `user_send_to_first_token` latency (user Send tap to the first streamed Gemma conversation-output chunk, tagged `operation=chat_conversation`) via the existing `GemmaTimingTrace` mechanism, distinct from the canned-reply/card timings it already tracked

### Results page, Ask and reference data — 2–4 October 2026

- [x] Find: Variety filter (404 grapes), Sweetness removed, "Enter a Spec" header, score tabs (91–100 default, 80–90), Sort menu, skeleton loader, ten-at-a-time paging with More, seeded equal-score ordering, Reviews → Extended db → on-demand Brave Web Search; Gemma removed from Find
- [x] Chat hands finished Q1-Q3 answers to the shared Results page; Q3 drops sweetness; open conversation gains the on-demand lookups
- [x] Ask screen with grounded facts note, warm-up, per-question extras, order of trust, reminder, retry on empty reply, token-usage logging, 8,192-token window; welcome message with bold wine name and topic list
- [x] Reference data named and wired: Grape_Profile_Internal, Grape_Profile_Kaggle_Extracted, Wineries_Directory; country names standardised with Room migration 3→4; winery list preloaded in the background
- [x] 203 unit tests, 30 of which (`KaggleConversationResponderTest`, "SystemClock not mocked") fail as they did before these changes
- [~] `GuidedSelectionIntegrationTest` still expects the old alphabetical tie order; `androidTest` has been compiled but not run
- [ ] Physical-device verification of Ask/Chat lookups, the Results page, and the 8,192-token window across a long conversation
- [ ] Find web results are not saved to the Extended db; Grape_Profile_Internal covers 65 of 404 grapes (long tail relies on Kaggle-extracted data and Gemma's own knowledge); a release-build size has not been measured (debug APK about 99 MB)

### Menu, Directory and Wine Production — 4 October 2026

- [x] Main page Menu bottom sheet (About Uncork, Winery Directory, Wine Production)
- [x] Winery Directory: Country → Regions → Wineries pages, alphabetised, with counts, Back/Home bottom bar, "Directory" title with a breadcrumb on the page showing the path (Section 6C)
- [x] Wine Production: France → grape → place → steps (Merlot and Chardonnay have facts; other grapes pending)
- [ ] Content for About Uncork (placeholder only)
- [~] `WineryDirectoryScreenTest` compiles but has not been run on a device or emulator

### Accessibility — 28 September 2026 (WCAG 2.0 AA text contrast pass)

- [x] `InkMuted` (`0xFF9A8F82`), used for secondary/caption text and icon tints across Landing, Splash, Find, My List, the Profile Page, and Chat, measured 2.8:1 against the `Parchment` background — below the 4.5:1 WCAG 2.0 AA minimum for normal text and even the 3:1 minimum for large text/UI components. The existing `InkSubtle` (`0xFF6E645A`, 5.1:1 against `Parchment`) — already defined in `Color.kt` for this purpose but only applied on the Find screen — was extended to every remaining `InkMuted` text and icon usage across `LandingScreen.kt`, `SplashScreen.kt`, `WineResultCard.kt`, `FavoritesScreen.kt`, `StageShowScreen.kt`, and `ConversationScreen.kt`
- [x] The Find screen's unselected radio-dot indicator (`GuidedSelectionScreen.kt`'s `RadioDot`) measured 1.5:1 at its previous `InkMuted.copy(alpha = 0.45f)` stroke color, failing the 3:1 non-text/UI-component contrast minimum; changed to solid `InkSubtle`
- [x] `Wine` (8.8:1) and `Ink` (13.7:1) against `Parchment` were already compliant and unchanged. One purely decorative fill (the Find screen's filter-count pill background, `InkMuted.copy(alpha = 0.12f)`) was left as-is since it sits behind default dark text rather than being foreground content itself
- [ ] No automated contrast-regression check exists yet; this was a manual pass. A lint rule or test that flags a new text/icon color below 4.5:1 against its background would prevent regression

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
- [ ] Physical-device execution of instrumentation tests (`androidTest`) and final responsive visual QA — the Chat recommendation-flow rework's backend timing was confirmed live on a physical device via logcat (a Kaggle hit resolves in ~130ms; the Gemma/web mutex-contention regression is fixed), but the redesigned per-search-type master-card UI (bordered wine cards, line separators, the unified three-dot loader, the web-search-failure retry card) has not had a full visual walkthrough, and `androidTest` still has not been run (only compiled) for lack of an emulator/AVD — this includes the 45-second Profile Page load timeout, the progressive card-streaming UI, and (new as of 27 September 2026) the per-field loading spinners on the Profile Page while an on-demand detail call is in flight (`StageShowScreenTest.rendersKnownCardDataWhileMissingDetailsLoad` is written and compiles but, like the rest of `androidTest`, has not run on a device or emulator) — all untested against genuine on-device inference latency. The PR #19 card-consistency/Gemma-hardening changes did get a live physical-device spot check (`adb install -r`, data preserved) — that pass is what surfaced the field-echo and hallucinated-markdown bugs fixed in Bug-Log.md #7 — but it was manual observation of a handful of Find/Chat searches, not a full pass of every affected screen or a repeated-trial confirmation that the new validation rejects every recurrence of those failure modes.
- [ ] Both progressive-card-streaming benchmark documents (25 September) are single uncontrolled runs (no repeated trials, no thermal/GPU-clock control) — the reported ~28% latency improvement from dropping `summary` from the (now-superseded) single-stage full-profile prompt needs repeated-trial confirmation before being treated as a proven, stable gain rather than a promising single-run signal. The same caveat applies to the newer two-stage on-demand-detail measurement (6.28s initial / 8.68s detail-fill, `Docs/Gemma-Performance-Optimization-Summary-2026-09-25.md`).
- [ ] `guided_instruction.txt` is now dead code (Section 6A, GS-AC7) — a small cleanup, not a behavior gap, but not yet actioned

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
