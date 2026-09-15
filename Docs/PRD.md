# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Last updated:** 15 September 2026
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Gemma generates the initial suggestion from its trained knowledge: a variety, its best or most popular country and province, and a flavor and attribute summary. Complex or high-stakes queries may still escalate to a cloud LLM. If Gemma resolves variety, country, and province, the app searches Kaggle with that combination for up to three real-world options. If any of those fields is `Unknown`, it searches Kaggle using keywords from the original user query. A Kaggle keyword hit fills Gemma's missing fields and supplies up to three options. Either Kaggle search falling to zero matches triggers an in-scope live web search, which resolves variety, country, province, and other fields where possible and supplies up to three web options. Kaggle therefore follows and depends on Gemma's response rather than acting as an independent comparison. Newly resolved web-search profile data is written to the growing on-device `VarietyRegionProfile` Room table with source `web_search`.

## 2. MVP Scope

Five screens:
1. Splash Screen
2. Main Conversation Screen
3. Profile Page
4. History
5. My List

## 3. Out of Scope (this MVP)

- Suggestions / discovery feed (a separate curated/"surprise me" feed — distinct from History, which is your own past suggestions)
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
flavor_notes  (2-4 short tags)
review_summary (nullable string, full untruncated Kaggle review text)
web_summary   (nullable string, 1-2 sentences)
```

Critic `rating` is a nullable integer reserved for the Kaggle `points` field. It is not requested from or populated by Gemma.

`review_summary` is populated only when the app matches a real Kaggle review. It retains the full, untruncated review text with no sentence or character cap. Gemma and the web-search fallback never populate it. `web_summary` remains limited to a short AI-synthesized paraphrase.

`web_summary` is populated only for an option resolved through the existing web-search fallback after Kaggle misses or fails under AC10e or AC10f. It is an AI-synthesized paraphrase of the search findings, never verbatim source-page text. Gemma suggestions and Kaggle matches never populate it. This does not add a new user action or allow web search to run when Kaggle has returned matches.

All profile keys are static on the Profile Page. The full field set—variety, country, province, body, tannin, acidity, flavor notes, winery, rating, review summary, and web summary—always renders regardless of source. Unrecognized or unresolved fields render as `Unknown` rather than being guessed or omitted. `Unknown` is styled in muted or secondary text, visually distinct from resolved values.

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

### Response-source rules

- Gemma produces the initial recommendation. Kaggle then searches either its resolved country-province-variety or, when any of those values is `Unknown`, the original user-query keywords.
- Kaggle options may supply a real wine name, winery, post-aggregation critic points, and full reviewer text. More than three matches are reduced using `ORDER BY points DESC, winery ASC`, making winery alphabetical order the deterministic tiebreak for tied or null points.
- Either Kaggle search returning no matches or failing technically triggers the category-data fallback. When an exact profile is available, the app first checks Room for its cached option and may supplement it with live results; otherwise it runs the full web search. Newly found web options retain the search engine's result order. On a full search, the first option is cached. On a supplementary search, the existing cached option remains first and the new options are session- and History-only. Each web option receives its own AI-synthesized `web_summary`. Web options never receive critic rating or review summary values.
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

**Purpose:** Single chat-style interface, casual and personable in tone. Gemma supplies the initial recommendation, which automatically drives a Kaggle search and the defined fallback chain. The detailed profile lives on the Profile Page.

### Navigation & Display
- **AC1:** Given the app has passed splash, when the Main Conversation Screen loads, then a persistent text input is displayed at the bottom of the screen.
- **AC2:** Given a prior response exists in the current session, when the screen renders, then the conversation thread displays above the input, most recent at the bottom.
  - **AC2a:** Given the app is closed and reopened (a new session), when the Main Conversation Screen loads, then the screen starts fresh — the previous session's suggestions are not shown inline, but remain accessible under History (Section 8), grouped by date.
  - **AC2b:** Given the LLM can produce any number of suggestions within one session, when the user's query shifts to a different topic within the same session, then earlier suggestion cards remain visible in the thread rather than being cleared or collapsed.

### Content Tone
- **AC3:** Given a query is submitted, when Gemma responds, then the response consists of three parts in order: a warm greeting acknowledging the query; a suggested variety and its best or most popular country and province; and a casual-prose summary of flavor notes, tannin, acidity, and body.
- **AC3a:** Given Gemma's suggestion is generated, when the app checks whether variety, country, and province were all resolved under AC10, then it automatically searches Kaggle immediately afterward. It uses the resolved country-province-variety when all three values are known, or keywords from the original user query when any value is `Unknown`, with no separate user action.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC4a:** Given Gemma is asked to produce a structured profile, then its hidden output includes `variety`, `country`, `province`, `body`, `tannin`, `acidity`, and `flavor_notes`. The machine-readable payload is removed before the response is displayed. Gemma does not produce `rating`, `review_summary`, or `web_summary`.
- **AC5:** Given cheese is not requested, when a response is generated, then no cheese pairing is included by default.
  - **AC5a:** Given the user explicitly requests a cheese pairing, then a cheese suggestion is appended in the same casual tone.

### Wine options
- **AC6:** Given Kaggle or the web-search fallback returns one or more matches, when the app displays them, then up to three option cards render inline in the chat thread. Each card shows the wine's name, winery, and rating, displaying `Unknown` for any unresolved field.
  - **AC6a:** Given the user taps an option card, when tapped, then the app navigates to the Profile Page with that option's full field set. Opening a card does not save it.
  - **AC6b:** Given an option matches a wine already in My List, when it appears in the thread, then it shows the existing personal rating or a `Saved` annotation if unrated, with tap-through access to the saved record and notes.
  - **AC6c:** Given more than three Kaggle matches exist, then the app displays the first three from `ORDER BY points DESC, winery ASC`. Winery alphabetical order is the deterministic tiebreak for tied or null points.
  - **AC-KaggleRanking-Nulls:** Given Kaggle matches for a country-province-variety are ranked for display, then every match with a real `points` score ranks above every match with a null score, regardless of winery name. Null is always the lowest tier and is never mixed among scored matches alphabetically. Winery ascending breaks ties only among matches with the same points value or among matches whose points are all null. Raw SQLite implements this correctly with `ORDER BY points DESC, winery ASC` because null values sort last for a descending column. If ranking occurs after retrieval in Kotlin or another layer, the comparator must implement nulls-last explicitly.
  - **AC6d:** Given web search returns options, then the app displays up to the first three usable results in the search engine's existing order without applying custom ranking. If fewer than three usable results exist, only those results render.

### Error Handling
- **AC7:** Given a cloud LLM call or live web search fails, when this occurs, then an inline error is shown in the thread with a retry option in the same casual tone. No fabricated content is displayed in place of the failed operation.
  - **AC7a:** A Kaggle query failure does not use this error pattern. It proceeds to the web fallback under AC10e or AC10f in the same way as a clean zero-match result; Kaggle never stops the response on its own.
  - **AC7b:** AC10j also does not use the retry pattern when the web search cannot be reached because the device has no internet connection and no cached option is available. A retry control is not shown because the same action cannot succeed until connectivity returns.
  - **AC7c:** AC10k does not replace the response with this error pattern when a cached option remains usable. The cached card renders with a short note that additional options could not be found.

### Empty States
- **AC8:** Given no query has been submitted yet, when the screen first loads, then an empty state invites the first query via input placeholder text. No fabricated example results are shown.

### Eventing
- **AC9:** Given a query is submitted, then log locally: query text length, routing decision, whether Gemma resolved variety, country, and province, which Kaggle query path ran, whether Kaggle missed or failed technically, whether the category-data fallback ran, whether a cached option was used, whether supplementary search degraded to the cached option, whether web data was written to Room, the selected option source, and any winery-verification result. Personal-use MVP — local logging only, no analytics backend.

### Kaggle fallback chain
- **AC10:** `variety`, `country`, and `province` are mandatory for this flow. Given Gemma's initial response is generated, when evaluated, then the app checks whether all three fields contain resolved values rather than `Unknown`.
  - **AC10a:** Given all three mandatory fields are resolved, then Kaggle is queried using that country-province-variety.
  - **AC10b:** Given any mandatory field is `Unknown`, whether partial or full non-resolution, then Kaggle is queried using only keywords from the original user query. The app does not run a hybrid search using the mandatory fields Gemma resolved; those values are discarded for search purposes and may be backfilled from a Kaggle match under AC10d.
  - **AC10c:** Given the AC10a query returns one or more matches, then up to three option cards are shown under AC6.
  - **AC10d:** Given the AC10b query returns one or more matches, then all three mandatory fields are backfilled from the matched Kaggle data, including any values Gemma had resolved before the keyword search, and up to three option cards are shown under AC6.
  - **AC10e:** Given the AC10a query returns zero matches or fails to execute because of a read, parse, or other technical error, then the app proceeds directly to AC10g. It does not retry Kaggle with keywords because Gemma's resolved fields were the best available Kaggle input. A Kaggle technical failure is treated like a clean miss and never halts the response.
  - **AC10f:** Given the AC10b query returns zero matches or fails to execute, then the app proceeds to the same fallback in AC10g. A Kaggle technical failure is treated like a clean miss.
  - **AC10g:** Given the fallback runs after AC10e or AC10f, then the following rules apply:
    - When AC10e triggered the fallback, the app first checks `VarietyRegionProfile` for a cached option at the exact country-province-variety. If one exists, it is shown immediately as the first card with the `web_summary` stored when it was originally cached. When the device is online, a live search also runs for up to two additional options, merged with the cached card to a maximum of three. Each newly found supplementary card receives its own newly synthesized `web_summary`. These supplementary cards and summaries remain in the session and History only; they are not persisted to Room and do not replace or update the cached first card. When the device is offline, only the cached card is shown and AC10j does not apply.
    - When AC10e triggered the fallback and no cached option exists, the app runs the full web search to resolve variety, country, province, and other fields and find up to three options.
    - When AC10f triggered the fallback, no resolved combination exists for a cache lookup, so the full web search always runs using the original user-query keywords.
    - Every newly found web option retains the search engine's result order. Each returned option, up to three, receives its own independently synthesized `web_summary`. Rating and review summary display as `Unknown` for cached and newly found web options because both are reserved for a real Kaggle match. This fallback is part of the MVP.
    - The generation that produces each `web_summary` also resolves that option's structured `body`, `tannin`, and `acidity` values. Prose and structured attributes come from one generation step rather than separate calls.
  - **AC10h:** Given AC10g performs a full live web search because no cached option exists, when it resolves new field-level data and options for a country-province-variety combination, then the app writes the profile and first returned option, including winery, `web_summary`, `body`, `tannin`, `acidity`, and other resolved fields, to `VarietyRegionProfile` with source `web_search`. The write replaces any row for the same `country` + `province` + `variety` composite key. Only that first-position result is cached; the other options remain in the session thread and History entry. Given AC10g instead begins with an existing cached first-position option and runs a supplementary live search, then none of the supplementary results are written to Room. No custom web ranking is applied. Web-derived `body`, `tannin`, and `acidity` values carry the internal trailing `*` thin-evidence marker.
  - **AC10i:** Given a full web search fails or returns nothing usable, then AC7 applies: the app shows an inline retry error in a casual tone and presents no fabricated content.
  - **AC10j:** Given AC10g requires a live web search but the device has no internet connection, and either no cached option exists for the resolved country-province-variety or the request followed AC10f with no combination available to look up, then the response plainly explains that web search could not run without a connection and that Gemma's knowledge and the on-device Kaggle data do not contain enough information to answer accurately. The message uses the thread's casual tone and does not show a retry control. When a cached option exists, AC10g's offline branch applies and this failure message is not shown.
  - **AC10k:** Given AC10g finds a cached option and the device is online, but the supplementary search for up to two additional options fails or returns nothing usable, then the cached card still renders with a short, casual inline note explaining that additional options could not be found. This is not an AC10j offline dead end or an AC10i full failure because a usable cached answer remains available.

**Open assumptions:**
- Exact routing heuristic for cloud escalation (word count? explicit constraint count?) is not yet defined — needed before this can be built.
- Assumed cheese pairing is requested via a dedicated action, not by re-parsing free text for intent — confirm this matches your expectation.
- Exact styling and visual treatment of the wine option-card section within the chat response.
- Whether structured prompt extraction and History's lightweight `Your Request:` keyword extraction should share one mechanism or remain separate.

---

## 7. Profile Page

**Purpose:** Full-screen, distraction-free showcase of a single wine. Deliberate and factual in tone — the opposite register from Main Conversation's casual suggestions.

### Navigation & Display
- **AC1:** Given the user taps an option card, Gemma's own suggestion, a My List entry, or a History entry, when the Profile Page opens, then it displays that specific wine's name, origin, and key traits in large typography, with no persistent navigation chrome.
- **AC2:** Given no bottle imagery is used in MVP scope, when the Profile Page renders, then typography carries the full visual weight, with no image or image placeholder.

### Data & Content — Key for Wine Description
- **AC4:** Given the user navigates to the Profile Page from a tapped option card or Gemma's own suggestion, when the page renders, then it displays the full static field set from that entry with no source switch:
  - `variety`
  - `country`
  - `province`
  - `body` (single value | range | optional trailing `*` | Unknown)
  - `tannin` (single value | range | optional trailing `*` | Unknown)
  - `acidity` (single value | range | optional trailing `*` | Unknown)
  - `flavor_notes`
  - `winery`
  - `rating`
  - `review_summary`
  - `web_summary`
- **AC4a:** Given the entry came from a Kaggle option, when rendered, then winery, rating, and the full untruncated review summary display resolved values where the matched Kaggle record supplies them. When duplicate-wine aggregation combined multiple reviews, the labeled concatenation is displayed without dropping or shortening any review. Web summary displays as `Unknown`.
- **AC4b:** Given the entry came from Gemma's own suggestion without a matched option, when rendered, then winery, rating, review summary, and web summary display as `Unknown`; they are not omitted from the layout.
- **AC4c:** Given the entry came from a web-search-sourced option under Section 6, AC10g, when rendered, then winery, variety, country, province, web summary, and other attributes display where the search resolved them. Web summary is labeled `AI summary` and is visually distinct from the `Critic review` treatment used for review summary. Rating and review summary display as `Unknown` because both are reserved for a real Kaggle match.
- **AC4d:** Given `body`, `tannin`, or `acidity` contains the internal trailing `*` thin-evidence marker, whether from fewer than three supporting Kaggle reviews or a web-derived synthesis, when the Profile Page renders that value, then it displays the plain-language annotation `Insufficient data` near the value using the same treatment for either source. The raw `*` character is not shown to the user. The exact caption, tooltip, or icon treatment remains to be defined.
- **AC5:** Given a shared attribute value is `Unknown`, when displayed, then it renders in muted or secondary text, visually distinct from resolved values.
- **AC7:** Given a Kaggle option's winery has been separately verified through a winery-verification web search, when the Profile Page renders, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
  - **AC7b:** The winery-verification search checks one specific Kaggle winery. It is separate from the category-data fallback web search in Section 6, AC10g, which retrieves options and resolves missing attributes after a Kaggle miss. The implementation must keep these as distinct search paths.
- **AC8:** Given the wine has a cheese pairing attached, when the Profile Page renders, then the pairing displays as a secondary section below the wine details, not as the primary focus.

### Actions
- **AC9:** Given the Profile Page is open, when it renders, then a `Suggested pairing` field and its concise content are visible upfront with the other profile details; no pairing action button is shown.
- **AC10:** Given the Profile Page is open, when the user scrolls its content, then the circular, center-aligned burgundy `Save` control remains fixed in the bottom navigation area. Tapping it saves the wine to My List (Section 9) and changes the control to a lighter muted state labeled `Saved`. Tapping `Saved` removes the wine and restores the burgundy `Save` state. The control and My List tiles do not display heart icons.
  - **AC10a:** Personal rating is display-only on the Profile Page. A saved rating displays as `Your rating · n / 10`; when absent, the page displays `You have not tried this wine` and provides no interactive rating scale.

---

## 8. History

**Purpose:** Browse past suggestions across sessions, grouped by date. The way back into anything you saw before, whether or not you saved it.

### Navigation & Display
- **AC1:** Given at least one session (past or current) contains suggestions, when the History tab is opened, then suggestions are grouped under date headers (e.g. "Today," "Yesterday," or a calendar date), most recent group first.
- **AC2:** Given the current session has active suggestions, when History is opened mid-session, then those suggestions also appear, grouped under today's date — consistent with the in-session persistence in Section 6, AC2b.

### Data & Content
- **AC3:** Given a suggestion entry in History, when displayed, then it shows the wine name and `Your Request:` followed by useful keywords extracted locally from the user's original prompt, such as country, province, variety, color, body, acidity, tannin, or flavor. Attributes not stated by the user are not added. AI conversation text and the full factual schema are not shown; the schema stays on the Profile Page.
- **AC4:** Given a suggestion in History matches a wine already in My List, when displayed, then an annotation (rating, or a "Saved" mark if unrated) is shown with a tap-through link to that saved wine's full record and notes — the same behavior as Section 6, AC6b.
- **AC5:** Given the user taps a History entry, when tapped, then the app navigates to the Profile Page with that wine's data, identical to tapping a live suggestion.

### Actions
- **AC6:** Given a History entry is not yet saved, when the user saves it directly from History (without necessarily opening the Profile Page first), then it is added to My List the same way as saving from the Profile Page (Section 7, AC10).
- **AC6a:** Given History contains entries, Clear uses a black-at-10%-opacity background and Clear All remains hidden. Clear displays empty checkboxes for manual selection and changes to Cancel until selection is exited. A floating trash-can icon and Delete label appear above the bottom navigation, stay disabled without a selection, and remove the selected entries from local History when tapped.

### Data Synchronization
- **AC7:** Given this is a single-device personal MVP, when suggestions are logged, then History persists in local on-device storage only. No cloud sync.

### Empty States
- **AC8:** Given no suggestions have been made yet, when History is opened, then its content area remains blank beneath the shared header.

**Open assumptions:**
- How far back History retains suggestions — indefinitely, or with a rolling cutoff (e.g. 90 days)? Not yet defined, and matters for on-device storage growth since there's no server-side cap in this MVP.
- Date-grouping granularity — assumed per-day for now; per-session-within-a-day is an alternative if a single day produces many unrelated suggestions.

---

## 9. My List

**Purpose:** The user's saved wines, with an optional personal rating and notes.

### Navigation & Display
- **AC1:** Given the user taps `Save` on the Profile Page (Section 7, AC10) or saves directly from a History entry (Section 8, AC6), when this happens, then the wine is added to My List, with rating and notes optional at that point. Simply viewing a suggestion or opening the Profile Page does not save it.
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
- **AC8:** Given no saved wines exist yet, when My List opens, then an empty state invites saving a wine from the Profile Page or History.

**Open assumptions:**
- Sort order for My List — not yet defined.
- Whether removing a saved wine requires a confirmation step — assumed yes, pending your input.

---

## 10. Open Assumptions & Unresolved Decisions (Full List)

Surfaced here for validation before development starts:

1. Splash: maximum acceptable load duration before "taking longer than usual" messaging.
2. Splash model progress is resolved: network download uses byte percentage, while local checking and SHA-256 verification use indeterminate progress.
3. Main Conversation: exact routing heuristic for on-device → cloud escalation.
4. Main Conversation: cheese pairing assumed to be a dedicated action, not free-text intent parsing.
5. Main Conversation: exact styling and visual treatment of the wine option-card section.
6. Main Conversation: whether structured query extraction and History's `Your Request:` extraction should share one mechanism or remain separate.
7. Profile Page: exact visual treatment for distinguishing `AI summary` from `Critic review`.
8. Profile Page: exact caption, tooltip, or icon treatment for the `Insufficient data` annotation.
9. History: retention window — indefinite vs. a rolling cutoff — not yet defined; affects on-device storage growth over time.
10. History: date-grouping granularity — assumed per-day.
11. My List: sort order for the list.
12. My List: confirmation step assumed required before removing a saved wine.
13. **Scope confirmation:** location/price lookup (Google Places), discussed earlier in this project, is not part of these five MVP screens — confirm this is an intentional deferral.
14. Final system prompt. The current structured-output instruction is implementation scaffolding and has not been approved as the final product prompt.

---

## 11. Implementation Status

Implemented on native Android with Kotlin and Jetpack Compose:

- Splash branding, authenticated resumable Gemma E2B download, byte progress, SHA-256 verification, three automatic retries, and manual retry state
- Offline LiteRT-LM conversation inference after model installation
- Shared Chat/History/My List header with left-side page title and right-aligned Uncork branding, Chat empty state, conversation thread, input composer, dark status-bar treatment, and labeled bottom navigation
- 16sp user and AI message text, Markdown-style `**bold**` rendering, 5% black AI background wash, and 1dp AI rule at 50% opacity
- Full-screen Profile Page navigation and layout, structured AI profile parsing, pairing action, saved state, and 10-dot rating interaction
- Persistent on-device History storage, date-grouped History list, Chat/History tab navigation, preserved in-session Chat state, and Profile Page entry navigation
- Current Room-backed `VarietyRegionProfile` storage with the original variety/province composite key, JSON flavor-note conversion, batch and single-row inserts, exact lookup repository, and off-main-thread seed-on-empty startup logic

Not yet complete:

- Final product-owned system prompt
- Frank Ruhl Libre font bundling
- Inclusion and device-level verification of the renamed `country_province_variety_profiles.json` seed asset; the Room pipeline is implemented, but the asset is not currently present in this checkout
- Gemma's three-part conversational response with distinct variety, country, and province values in its recommendation and hidden attribute profile
- Automatic Kaggle country-province-variety or original-query keyword matching, cascade-on-error behavior, duplicate-wine aggregation, deterministic `points DESC, winery ASC` selection with explicit nulls-last handling outside SQLite, full reviewer text, wine names on option cards, and per-card Profile Page navigation
- In-scope web fallback after either Kaggle query misses or fails, including per-option `web_summary` synthesis, first-result persistence, thin-evidence attribute markers, cached-first behavior, supplementary search degradation, and the distinct no-connection path
- Expansion of the Room entity, DAO lookup, and composite key from variety/province to country/province/variety; storage of one cached web option; runtime `web_search` write-back; and the related migration and tests
- Static, single-source Profile Page behavior described in Section 7, including ranged attribute strings, thin-evidence annotations, `Unknown` placeholders, and removal of the current comparison-toggle scaffold
- Cloud routing, category-data web search, first-result option selection, and the separate winery-verification search path
- Personal ratings and notes in My List
- Real model-generated cheese pairing on the Profile Page; the current Profile Page result is placeholder copy
- Physical-device execution of the Profile Page instrumentation tests and final responsive visual QA
