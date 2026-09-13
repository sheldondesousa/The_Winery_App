# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Last updated:** 13 September 2026
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Suggestions begin with an on-device LLM and may escalate to a cloud LLM for complex or high-stakes queries. A bundled on-device Kaggle database resolves local gaps and supplies matching bottles with genuine critic scores. Web search is the fallback when local sources cannot resolve variety/region or cannot find matching bottles. A separate on-device variety-region profile table is initially seeded from Kaggle-derived JSON and can grow when live web searches fill missing combinations.

## 2. MVP Scope

Five screens:
1. Splash Screen
2. Main Conversation Screen
3. Stage Show
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
region
body          (light | medium | full | Unknown)
tannin        (low | medium | high | Unknown)
acidity       (low | medium | high | Unknown)
flavor_notes  (2-4 short tags)
```

Critic `rating` is a nullable integer reserved for the Kaggle `points` field. It is not requested from or populated by Gemma.

Unrecognized/unextractable fields render as "Unknown" rather than guessed. `Unknown` is styled in muted/secondary text, visually distinct from resolved values.

For category-level requests such as "Malbec," the AI may use learned knowledge to provide typical variety characteristics even when no exact bottle is identified. Bottle-specific winery, vintage, critic rating, or provenance claims remain `Unknown` unless a separate source supplies them. Critic rating must come from a real Kaggle match.

### Variety-region profile storage

The app has a growing on-device Room table named `VarietyRegionProfile`, keyed by the combination of `variety` and `province`. Each row stores nullable body, tannin, and acidity values; a list of flavor notes; generation metadata; and a source value of either `kaggle_derived` or `web_search`.

- On first launch, if the table is empty, the app reads `variety_region_profiles.json` from packaged assets and inserts all profiles in one Room batch operation.
- Asset parsing and database writes run on an IO dispatcher so startup work does not block the interface.
- Later launches skip asset seeding when the table already contains rows.
- Replace-on-conflict inserts allow a future live web-search result to add or refresh one variety/province combination.
- A standalone repository currently supports exact variety/province lookup. It is intentionally not connected to the chat recommendation flow yet.
- The profile table does not provide critic ratings. Those remain reserved for a future lookup against genuine Kaggle `points` data.

### Response-source terminology

- **AI sourced** covers values produced by on-device Gemma and values obtained through web search. The response does not visually distinguish between those two origins.
- **Database** or **Kaggle** refers only to matches from the bundled Kaggle dataset.
- No citations are displayed in this response and detail flow.

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
- **AC4b:** Given the variety-region Room table is empty when the app starts, then its packaged JSON asset is parsed and inserted off the main thread. Given the table already contains rows, seeding is skipped without replacing runtime additions.

### Error Handling
- **AC5:** Given the on-device model load fails, when the user taps retry, then the model load is re-attempted.
  - **AC5a:** Given retry fails three consecutive times, then a message advises checking device storage or compatibility. No cloud fallback applies to this check — it is specifically verifying the on-device model.

**Open assumptions:**
- Maximum acceptable splash duration before showing a "taking longer than usual" message — not yet defined. Suggest 3-5 seconds as a placeholder pending device testing.
- On-device path is settled on the pinned `gemma-4-E2B-it.litertlm` artifact and LiteRT-LM runtime. Download progress is byte-based; local checking and SHA-256 verification are indeterminate phases.

---

## 6. Main Conversation Screen

**Purpose:** Single chat-style interface, casual and personable in tone. It surfaces a suggestion, not a data sheet — the detailed, factual breakdown lives in Stage Show.

### Navigation & Display
- **AC1:** Given the app has passed splash, when the Main Conversation Screen loads, then a persistent text input is displayed at the bottom of the screen.
- **AC2:** Given a prior response exists in the current session, when the screen renders, then the conversation thread displays above the input, most recent at the bottom.
  - **AC2a:** Given the app is closed and reopened (a new session), when the Main Conversation Screen loads, then the screen starts fresh — the previous session's suggestions are not shown inline, but remain accessible under History (Section 8), grouped by date.
  - **AC2b:** Given the LLM can produce any number of suggestions within one session, when the user's query shifts to a different topic within the same session, then earlier suggestion cards remain visible in the thread rather than being cleared or collapsed.

### Content Tone
- **AC3:** Given the on-device or cloud model generates a response, when phrased, then it uses casual, personable language (e.g. "This could work well — a Malbec would bring some dark fruit into it") rather than a structured field:value breakdown. The casual text and its bottle-tile section render together as one chat response.
- **AC3a:** Given response data came from on-device Gemma or web search, when it is displayed, then it uses the single label `AI sourced`. Gemma and web-search values are not visually distinguished and no citations are shown.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC4a:** Given Gemma is asked to produce a structured profile, then its hidden output includes `variety`, `region`, `body`, `tannin`, `acidity`, and `flavor_notes`. It also extracts `country`, `province` or region-adjacent terms, and any body, tannin, acidity, or flavor descriptors explicitly present in the user's prompt. The machine-readable payload is removed before the response is displayed.
- **AC4b:** Given both Gemma's `variety` and `region` are known, when the structured profile is parsed, then the app proceeds directly to bottle-tile sourcing under AC6.
- **AC4c:** Given either Gemma's `variety` or `region` is `Unknown`, when the structured profile is parsed, then the app queries the bundled on-device Kaggle database using other extracted prompt details. `country` and `province` may be exact-match filters because they are indexed Kaggle columns. Body, tannin, acidity, and flavor descriptors may only be lower-confidence fuzzy signals against review-description text; they are not exact filters.
- **AC4d:** Given the Kaggle query still cannot resolve variety or region, then the app attempts a web search to resolve them and fill the remaining structured attribute keys.
- **AC4e:** Given variety/region is resolved by Gemma, Kaggle, or web search—or the user explicitly accepts unresolved values through AC7c—then the app proceeds to bottle-tile sourcing.
- **AC5:** Given cheese is not requested, when a response is generated, then no cheese pairing is included by default.
  - **AC5a:** Given the user explicitly requests a cheese pairing, then a cheese suggestion is appended in the same casual tone.

### Bottle-tile sourcing
- **AC6:** Given variety and region are known, when the app sources bottle options, then it first queries the bundled on-device Kaggle database for matching bottles.
  - **AC6a:** Given Kaggle has matching bottles, then the response renders one or more tiles showing `winery` and `rating`. Rating is the genuine Kaggle `points` value and is never generated or inferred by Gemma or web search.
  - **AC6b:** Given Kaggle has no matching bottles, then the app performs a web search for alternative options.
  - **AC6c:** Given web search returns bottle options, then each tile shows `winery` and `title` and omits rating entirely.
  - **AC6d:** Given web-search bottle tiles are generated, then the same web-search call also retrieves the full attribute data needed by Stage Show: variety, region, body, tannin, acidity, and flavor notes. This is an eager fetch; attributes are not deferred until tile tap.
  - **AC6e:** Given a response contains multiple bottle tiles, when the user taps one tile, then the app opens Stage Show with that specific bottle's data. Every tile is a separate Stage Show entry point. Opening a tile does not save it.
  - **AC6f:** Given a tile matches a wine already in My List, when it appears in the thread, then it shows the personal rating or a `Saved` annotation if unrated, with tap-through access to the saved record and notes.

### Web attribute fetch timing

Web-search bottle attributes use an eager fetch: the same call that produces the alternative tile list also retrieves the Stage Show attributes for every returned tile. This avoids a second request and a loading state after a tile is tapped. The accepted trade-off is that the app pays the latency and cost for every generated web tile, including tiles the user never opens. If local event logs show a high Kaggle-miss rate, this decision should be revisited in favor of fetching full attributes only after a tile is tapped.

### Error Handling
- **AC7:** Given the cloud LLM call fails (no network, invalid API key, etc.), when this occurs, then an inline error is shown in the thread with a retry option, in the same casual tone. No fabricated content is displayed in place of a failed call.
- **AC7a:** Given Gemma and Kaggle cannot resolve variety/region and the web-resolution step cannot run because there is no internet connection, then the app explains that local resources did not contain enough information and an internet connection is needed. This error is separate from the cloud-LLM failure in AC7 and offers `Retry` and `Decline` actions.
- **AC7b:** Given the user selects `Retry` on the structured-resolution error, then the app attempts the web-resolution step again.
- **AC7c:** Given the user selects `Decline`, then unresolved keys remain `Unknown` and the app continues to bottle-tile sourcing without fabricating values.

### Empty States
- **AC8:** Given no query has been submitted yet, when the screen first loads, then an empty state invites the first query via input placeholder text. No fabricated example results are shown.

### Eventing
- **AC9:** Given a query is submitted, then log locally: query text length, routing decision, whether Gemma left variety/region unresolved, whether Kaggle resolved that gap, whether bottle matching reached the web-search fallback, the selected tile source, and any verification result. Track the Kaggle-miss rate so the cost and latency of eager web fetching can be reassessed. Personal-use MVP — local logging only, no analytics backend.

**Open assumptions:**
- Exact routing heuristic for cloud escalation (word count? explicit constraint count?) is not yet defined — needed before this can be built.
- Assumed cheese pairing is requested via a dedicated action, not by re-parsing free text for intent — confirm this matches your expectation.
- Exact styling and visual treatment of the bottle-tile section within the chat response.
- If the user's prompt yields no country or province for the local gap-resolution query, whether the app should skip directly to web search.
- Whether structured prompt extraction and History's lightweight `Your Request:` keyword extraction should share one mechanism or remain separate.
- Behavior when variety/region is known but Kaggle has no bottle matches and the alternative-options web search is unavailable.

---

## 7. Stage Show

**Purpose:** Full-screen, distraction-free showcase of a single wine. Deliberate and factual in tone — the opposite register from Main Conversation's casual suggestions.

### Navigation & Display
- **AC1:** Given the user taps a bottle tile (Section 6, AC6e), a My List entry, or a History entry (Section 8, AC5), when Stage Show opens, then it displays that specific wine's name, origin, and key traits in large typography, with no persistent nav chrome.
- **AC2:** Given no bottle imagery is used in MVP scope, when Stage Show renders, then typography carries the full visual weight — no image or image placeholder is shown.
- **AC3:** Given the user tapped a Kaggle-sourced tile, when Stage Show renders, then an `AI` / `Kaggle` toggle appears. The Kaggle side represents that specific review and the AI side represents the corresponding AI-sourced profile.
  - **AC3a:** Given the user tapped a web-search-sourced tile, when Stage Show renders, then no AI/Kaggle toggle appears because no Kaggle match exists. The single profile is labeled `AI sourced`.
  - **AC3b:** Given Stage Show displays AI-sourced or Kaggle data, then no citations are shown.

### Data & Content — Key for Wine Description
- **AC4:** Given the `AI` toggle position is active, or a web-search tile has no toggle, when Stage Show renders, then the shared schema fields display as `AI sourced`. This label covers both on-device Gemma values and web-search-derived values without visually distinguishing them:
  - `variety`
  - `region`
  - `body` (light | medium | full | Unknown)
  - `tannin` (low | medium | high | Unknown)
  - `acidity` (low | medium | high | Unknown)
  - `flavor_notes`
- **AC4a:** Given the `Kaggle` toggle position is active, when Stage Show renders, then the attributes are extracted from that tile's specific Kaggle review-description text. Its critic rating displays only when that review has a real Kaggle `points` value.
- **AC4b:** Given a web-search-sourced tile opens Stage Show, then all attributes fetched eagerly with the tile list are displayed immediately. Stage Show makes no second network request and shows no attribute-loading state.
- **AC4c:** Given no Kaggle comparison exists, when Stage Show renders, then available AI-sourced fields remain populated. Absence of Kaggle is not a reason to replace them with `Unknown`.
- **AC5:** Given a field value is Unknown on either toggle position, when displayed, then it renders in muted/secondary text, visually distinct from resolved values.
- **AC6:** Given both an AI suggestion and a Kaggle match exist, when Stage Show renders (regardless of which toggle position is active), then an agreement indicator is shown near the toggle ("Similar pick" / "Different take") based on variety + region overlap between the two.
- **AC7:** Given the "Kaggle" toggle is active and the matched winery has been verified via web search, when this is the case, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
- **AC8:** Given the wine has a cheese pairing attached, when Stage Show renders, then the pairing displays as a secondary section below the wine details, not as the primary focus, and independent of which toggle position is active.

### Actions
- **AC9:** Given Attributes is open, when it renders, then a `Suggested pairing` field and its concise content are visible upfront with the other profile details; no pairing action button is shown.
- **AC10:** Given Attributes is open, when the user scrolls its content, then the circular, center-aligned burgundy `Save` control remains fixed in the bottom navigation area. Tapping it saves the wine to My List (Section 9) and changes the control to a lighter muted state labeled `Saved`. Tapping `Saved` removes the wine and restores the burgundy `Save` state. The control and My List tiles do not display heart icons.
  - **AC10a:** Personal rating is display-only on Attributes. A saved rating displays as `Your rating · n / 10`; when absent, the page displays `You have not tried this wine` and provides no interactive rating scale.

**Open assumptions:**
- Which toggle position should be selected initially when a Kaggle-sourced tile opens Stage Show.

---

## 8. History

**Purpose:** Browse past suggestions across sessions, grouped by date. The way back into anything you saw before, whether or not you saved it.

### Navigation & Display
- **AC1:** Given at least one session (past or current) contains suggestions, when the History tab is opened, then suggestions are grouped under date headers (e.g. "Today," "Yesterday," or a calendar date), most recent group first.
- **AC2:** Given the current session has active suggestions, when History is opened mid-session, then those suggestions also appear, grouped under today's date — consistent with the in-session persistence in Section 6, AC2b.

### Data & Content
- **AC3:** Given a suggestion entry in History, when displayed, then it shows the wine name and `Your Request:` followed by useful keywords extracted locally from the user's original prompt, such as country, region, variety, color, body, acidity, tannin, or flavor. Attributes not stated by the user are not added. AI conversation text and the full factual schema are not shown; the schema stays on Stage Show.
- **AC4:** Given a suggestion in History matches a wine already in My List, when displayed, then an annotation (rating, or a "Saved" mark if unrated) is shown with a tap-through link to that saved wine's full record and notes — the same behavior as Section 6, AC6a.
- **AC5:** Given the user taps a History entry, when tapped, then the app navigates to Stage Show with that wine's data, identical to tapping a live suggestion.

### Actions
- **AC6:** Given a History entry is not yet saved, when the user saves it directly from History (without necessarily opening Stage Show first), then it is added to My List the same way as saving from Stage Show (Section 7, AC10).
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
- **AC1:** Given the user taps `Save` on Stage Show (Section 7, AC10) or saves directly from a History entry (Section 8, AC6), when this happens, then the wine is added to My List, with rating and notes optional at that point. Simply viewing a suggestion or opening Stage Show does not save it.
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
- **AC8:** Given no saved wines exist yet, when My List opens, then an empty state invites saving a wine from Stage Show or History.

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
5. Main Conversation: exact styling and visual treatment of the bottle-tile section.
6. Main Conversation: whether a prompt with no extractable country or province should skip directly to web resolution when Gemma leaves variety/region unknown.
7. Main Conversation: whether structured query extraction and History's `Your Request:` extraction should share one mechanism or remain separate.
8. Main Conversation: behavior when Kaggle has no bottle matches and the alternative-options web search is unavailable.
9. Stage Show: initial toggle position when a Kaggle-sourced tile is opened.
10. History: retention window — indefinite vs. a rolling cutoff — not yet defined; affects on-device storage growth over time.
11. History: date-grouping granularity — assumed per-day.
12. My List: sort order for the list.
13. My List: confirmation step assumed required before removing a saved wine.
14. **Scope confirmation:** location/price lookup (Google Places), discussed earlier in this project, is not part of these five MVP screens — confirm this is an intentional deferral.
15. Final system prompt. The current structured-output instruction is implementation scaffolding and has not been approved as the final product prompt.

---

## 11. Implementation Status

Implemented on native Android with Kotlin and Jetpack Compose:

- Splash branding, authenticated resumable Gemma E2B download, byte progress, SHA-256 verification, three automatic retries, and manual retry state
- Offline LiteRT-LM conversation inference after model installation
- Shared Chat/History/My List header with left-side page title and right-aligned Uncork branding, Chat empty state, conversation thread, input composer, dark status-bar treatment, and labeled bottom navigation
- 16sp user and AI message text, Markdown-style `**bold**` rendering, 5% black AI background wash, and 1dp AI rule at 50% opacity
- Full-screen Stage Show navigation and layout, structured AI profile parsing, pairing action, saved state, and 10-dot rating interaction
- Persistent on-device History storage, date-grouped History list, Chat/History tab navigation, preserved in-session Chat state, and Stage Show entry navigation
- Room-backed `VarietyRegionProfile` storage with a variety/province composite key, JSON flavor-note conversion, batch and single-row inserts, exact lookup repository, and off-main-thread seed-on-empty startup logic

Not yet complete:

- Final product-owned system prompt
- Frank Ruhl Libre font bundling
- Inclusion and device-level verification of the `variety_region_profiles.json` seed asset; the Room pipeline is implemented, but the asset is not currently present in this checkout
- Expansion of structured query extraction to include country, province, and user-stated attribute descriptors
- Gemma → Kaggle → web variety/region resolution, including the offline `Retry` / `Decline` path
- Kaggle bottle matching, multi-tile chat responses, genuine `points` ratings, and per-tile Stage Show navigation
- Web-search alternative tiles with eager attribute fetching and runtime `web_search` profile caching
- AI-sourced/Kaggle Stage Show behavior described in Section 7; current conditional toggle UI is only an unvalidated scaffold
- Cloud routing, web search, and winery verification
- Personal ratings and notes in My List
- Real model-generated cheese pairing on Stage Show; the current Stage Show result is placeholder copy
- Physical-device execution of the Stage Show instrumentation tests and final responsive visual QA
