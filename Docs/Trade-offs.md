# Trade-offs Log

**Status:** Living document
**Last updated:** 20 September 2026

Scope: decisions with a real cost on one side, made either during this build session (web search reliability, Chat's Q3 taste question, and the Find bottom sheet redesign) or earlier and already committed to in [PRD.md](PRD.md). This is not a full project history — it covers what was directly worked on and verified, not every historical commit.

Each entry states what was chosen, what was given up, and why.

---

## Web search reliability

### 1. Brave fetch cut from 12 results to 3
**Chose:** Fetch only Brave's top 3 web results per search instead of 12.
**Gave up:** Breadth of evidence — fewer candidate pages for Gemma to draw from, which could occasionally mean missing a good match that appeared only in results 4-12.
**Why:** The evidence text handed to the on-device model scales with result count. At 12 results the evidence payload measured ~14,000 characters — large enough that the small on-device Gemma model was prone to running past its output budget mid-JSON and returning nothing usable at all. Reliability of getting *any* answer was judged more valuable than marginal recall gains from more source pages, especially since the request only ever needs 3 output suggestions.

### 2. Strip HTML and cap each snippet to 300 characters
**Chose:** Sanitize (strip tags/entities) and hard-truncate each Brave result's description before it reaches Gemma.
**Gave up:** Some completeness of each source snippet — a long, detailed review paragraph gets cut at 300 characters, potentially losing supporting detail Gemma could have used.
**Why:** Raw Brave snippets included `<strong>`, `&#x27;`, and similar markup that added no information but consumed prompt budget. Combined with fix #1, this reduced total evidence size roughly 4x (14,092 → 3,454 characters in testing) with no loss of the parts of the text that actually carry meaning.

### 3. Per-item resilient JSON parsing over strict-batch parsing
**Chose:** Parse each wine object in Gemma's synthesized JSON array independently, skipping a malformed entry rather than failing the whole response.
**Gave up:** A small amount of strictness — a genuinely malformed response could now surface 1-2 valid cards instead of correctly detecting total failure and retrying.
**Why:** The original all-or-nothing parsing meant one bad entry (missing `name`/`country`, or a response cut short by the token budget) discarded every valid suggestion in the same batch. For a small on-device model summarizing noisy web text, an occasional imperfect entry is expected; losing two good results because of one bad one was a worse trade.

### 4. Relaxed required card fields (dropped the `country`-must-resolve rule)
**Chose:** Only require a wine's `name` to be resolved before it's shown; a card with `country = "Unknown"` is still displayed.
**Gave up:** Guaranteed completeness of every displayed card — a user might see a card missing its country.
**Why:** Requiring both `name` and `country` meant a wine the model was otherwise confident about (has a name, has attributes) could be discarded outright over a single unresolved field. Showing an incomplete-but-real card was judged better than showing nothing.

---

## Chat's Q3 taste question

### 5. One-word annotations instead of an on-demand explanation
**Chose:** Show `Body (weight)`, `Tannin (dryness)`, `Acidity (sourness)`, `Sweetness (sugar)` directly in the question.
**Gave up:** The richer, on-request explanation Gemma could previously have given for an unfamiliar term (the design specified in PRD AC3g-1, offering to explain terms).
**Why:** A fixed, always-visible one-word hint needs no model call, can't be wrong, and answers the most common confusion (what does "tannin" even mean) without adding a conversational round-trip. The trade is depth of explanation for zero-latency clarity for most users.

### 6. Keyword-vocabulary matching over model-interpreted answers (pre-existing architectural choice, retained)
**Chose:** Match a Q1/Q2/Q3 answer against curated keyword lists in Kotlin rather than asking Gemma to interpret it.
**Gave up:** Flexibility — a genuinely novel phrasing not in the vocabulary list gets an apology-and-repeat instead of being understood.
**Why:** This was the explicit reason for the "Rework Chat onboarding" redesign: the prior Gemma-led onboarding was unreliable — hidden markers leaked into replies, formatting was inconsistent, and basic answers sometimes failed outright. Deterministic matching trades some flexibility for consistent, debuggable behavior. The one escape hatch (a reply with `?` or 4+ words gets a real Gemma reply) preserves handling for genuine tangents.

---

## Find bottom sheet redesign

### 7. Single-select everywhere, including Type
**Chose:** Every Find field (Type, Country, Province, Sweetness, Tannin, Body, Acidity) is now single-select with an "Any" clear row and auto-dismiss on tap.
**Gave up:** Type's original "select any that apply" multi-select capability — a user can no longer ask for "red or rosé" in one Find search.
**Why:** Explicit user direction: Country and Province's single-select-and-close pattern was judged correct, and the inconsistency with the other five fields (which required a separate "Done" tap and stayed open for multiple picks) was the actual bug being fixed, not a feature to preserve selectively.

### 8. Kept `GuidedCriteria`'s underlying storage as `Set<String>` rather than migrating to a single `String`
**Chose:** Left the data model (`wineType: Set<String>`, etc.) unchanged; only the UI now ever writes 0 or 1 value into it.
**Gave up:** A fully accurate data model — the type still technically allows multiple values, which no longer reflects real UI behavior, so a future reader of `GuidedCriteria` could be misled.
**Why:** Downstream code (`filterCount`, `tags()`, `constraints()`, the database query builder) already handles a `Set<String>` generically and correctly with 0 or 1 elements. Migrating the type to `String` would have touched the query layer, the unit tests exercising multi-value sets (`GuidedSelectionStateTest`), and potentially the search backend's contract — a much larger, riskier change for a UI-only bug fix.

### 9. One shared row-rendering composable instead of two independently styled lists
**Chose:** Country/Province and the other five fields now render through the exact same `SingleSelectRow` composable rather than two separately maintained implementations.
**Gave up:** Nothing significant — this was a straight improvement. Noted here because it was itself a response to two rounds of the same font/height/indicator-size bug appearing from having two parallel implementations that drifted apart. The trade-off is really upstream: maintaining two look-alike UI paths cost more (in bugs) than it saved (in flexibility neither path actually used).

---

## Pre-existing architectural trade-offs (from PRD.md, referenced for context)

These were already committed to before this session and are documented in [PRD.md](PRD.md); listed here only because they shape why some of the above decisions were made the way they were.

- **On-device only, no cloud LLM fallback for Find or the deterministic Chat flow.** Gives up cloud-model quality and internet-independent recall for a fully offline, private, personal-use app.
- **Only one web-search result is cached per country/province/variety.** Gives up caching every web-derived option for simplicity — "caching supplementary options would require a separate linked table and is outside this design" (PRD §4).
- **SQLite keyword search instead of FTS5** for the Kaggle database, because Android's bundled SQLite may not provide FTS5. Gives up full-text search quality for guaranteed compatibility.
- **`BRAVE_SEARCH_API_KEY` delivered via a gitignored `local.properties` file**, acceptable for a personal single-user MVP but explicitly flagged in the PRD as not production-safe.
