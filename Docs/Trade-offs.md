# Trade-offs Log

**Status:** Living document
**Last updated:** 25 September 2026

Scope: decisions with a real cost on one side, made either during this build session (web search reliability, Chat's Q3 taste question, the Find bottom sheet redesign, and — from the "Card consistency and Gemma-hardening" section onward — the 25 September 2026 PR #19 cleanup) or earlier and already committed to in [PRD.md](PRD.md). This is not a full project history — it covers what was directly worked on and verified, not every historical commit.

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
**Status: superseded 25 September 2026 — see the "Card consistency and Gemma-hardening" section below.**

**Chose:** Left the data model (`wineType: Set<String>`, etc.) unchanged; only the UI now ever writes 0 or 1 value into it.
**Gave up:** A fully accurate data model — the type still technically allows multiple values, which no longer reflects real UI behavior, so a future reader of `GuidedCriteria` could be misled.
**Why:** Downstream code (`filterCount`, `tags()`, `constraints()`, the database query builder) already handles a `Set<String>` generically and correctly with 0 or 1 elements. Migrating the type to `String` would have touched the query layer, the unit tests exercising multi-value sets (`GuidedSelectionStateTest`), and potentially the search backend's contract — a much larger, riskier change for a UI-only bug fix.

**What changed:** the misleading type stopped being a passive risk and became an active one once `guided_instruction.txt` needed correcting (a user directly asked "the user can select only one value per question," and the prompt genuinely said "OR within a field") — a prompt correction alone would have been describing behavior the code's own types still contradicted. At that point the migration was no longer "a much larger, riskier change for a UI-only bug fix"; it was the fix for a real correctness bug (the stale union-matching prompt language) plus the schema-consistency work already underway in the same session. Done in full (state, SQL query builder, type filter, review matching, UI, Gemma validation), including deleting the now-truly-dead `toggled()` helper this entry originally left in place. See PRD.md GS-AC7/GS-AC8 and Bug-Log.md #7 for what motivated it.

### 9. One shared row-rendering composable instead of two independently styled lists
**Chose:** Country/Province and the other five fields now render through the exact same `SingleSelectRow` composable rather than two separately maintained implementations.
**Gave up:** Nothing significant — this was a straight improvement. Noted here because it was itself a response to two rounds of the same font/height/indicator-size bug appearing from having two parallel implementations that drifted apart. The trade-off is really upstream: maintaining two look-alike UI paths cost more (in bugs) than it saved (in flexibility neither path actually used).

---

## Card consistency and Gemma-hardening (25 September 2026, PR #19)

### 10. Deleted the dead second-stage profile-reload call instead of leaving it as a safety net
**Chose:** Remove `loadProfile()` and `profile_system_instruction.txt` entirely, rather than leaving the unreachable code in place "just in case."
**Gave up:** A one-time backfill path for pre-existing local favorites saved before the `profileComplete` field existed — those will now permanently show `Unknown` for `summary` instead of ever healing on next visit, however rare that case is.
**Why:** Every current card-creation path already sets `profileComplete = true`, so the call had already become dead code; per this project's own no-dead-code convention, unreachable code that isn't a documented product decision should be deleted, not kept "for later." The alternative (special-casing the healing behavior for just that one legacy scenario) was judged not worth the complexity for a cosmetic, local-only, one-time edge case.

### 11. Rejected malformed Gemma output outright rather than trying to salvage it
**Chose:** When a card's name is a schema-echo (`"name."`) or contains hallucinated markdown/URL markup, discard the whole card rather than attempting to strip the bad part and keep the rest.
**Gave up:** A recoverable card in cases where only the name was bad but other fields (variety, country, attributes) might have been usable — e.g. the hallucinated-citation card in Bug-Log.md #7 still had a real variety, country, and province.
**Why:** A wine card's name is its primary identity; a card salvaged from a corrupted name has no reliable way to confirm the rest of the payload is trustworthy either, and silently displaying a recommendation next to a repaired-but-possibly-wrong name risks looking more authoritative than it is. Rejecting outright is simpler to reason about and matches the existing precedent (`"Unknown Wine"` was already rejected the same way before this session).

### 12. Added a UI-level name filter as a second, independent line of defense
**Chose:** Filter out any card with a blank/`"Unknown"` name at render time (PRD.md AC10j), even though the parsers already reject such cards before they're ever published.
**Gave up:** A small amount of simplicity — this is deliberately redundant with the parser-side checks (Bug-Log.md #7), rather than trusting a single point of validation.
**Why:** Not every path that produces a `WineSuggestion` goes through the hardened parsers (e.g. a pre-existing local favorite, or a future source added without updating every validation site). A cheap, source-agnostic display-time backstop is worth keeping even though it should, in the current code, never actually trigger.

### 13. Required Guided Selection to name a real wine, accepting more hallucination risk
**Chose:** Change `guided_instruction.txt` so Guided Selection asks for a real, known wine name "from knowledge," matching Chat's card-search prompt, instead of describing a plausible style/category with no named bottle.
**Gave up:** Some of the earlier design's safety margin — asking a small on-device model to name a specific real-world entity is inherently more hallucination-prone than asking it to describe a category (this is very likely part of why Bug-Log.md #7's malformed-name failures surfaced when they did).
**Why:** Direct user instruction, for card-display consistency between Chat and Find — the two surfaces are meant to feel like the same product. The added hardening in trade-offs #11-12 exists specifically to absorb the extra risk this decision introduced, rather than leaving Guided Selection's cards unprotected against it.

### 14. Kept four separate Gemma prompts instead of merging them into one
**Chose:** After aligning field names and the real-name requirement between `chat_search_instruction.txt` and `guided_instruction.txt`, left them as two separate prompt files (plus `curious_chat_instruction.txt` and `web_result_system_instruction.txt`) rather than merging any of them.
**Gave up:** A smaller number of prompt files to maintain, and a single source of truth for "produce wine cards."
**Why:** Chat search and Guided Selection receive genuinely different input shapes (a flat, always-fully-populated preferences object vs. a sparse, single-value-per-selected-field constraint map) and use different validation strictness (permissive best-effort parsing vs. strict `require()`-per-field). Encoding both input modes and both validation philosophies into one prompt would mean the model has to infer which mode it's in from the input shape alone — exactly the kind of added ambiguity that a small on-device model handles poorly, as this session's own hardening work (Bug-Log.md #7) already demonstrated at the current level of complexity.

---

## Pre-existing architectural trade-offs (from PRD.md, referenced for context)

These were already committed to before this session and are documented in [PRD.md](PRD.md); listed here only because they shape why some of the above decisions were made the way they were.

- **On-device only, no cloud LLM fallback for Find or the deterministic Chat flow.** Gives up cloud-model quality and internet-independent recall for a fully offline, private, personal-use app.
- **Only one web-search result is cached per country/province/variety.** Gives up caching every web-derived option for simplicity — "caching supplementary options would require a separate linked table and is outside this design" (PRD §4).
- **SQLite keyword search instead of FTS5** for the Kaggle database, because Android's bundled SQLite may not provide FTS5. Gives up full-text search quality for guaranteed compatibility.
- **`BRAVE_SEARCH_API_KEY` delivered via a gitignored `local.properties` file**, acceptable for a personal single-user MVP but explicitly flagged in the PRD as not production-safe.
