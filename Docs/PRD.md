# PRD: Wine & Cheese Pairing App — MVP

**Status:** Draft
**Author:** Sheldon
**Platform:** Android only, native (Kotlin) — matches your Pixel 10 Pro Fold
**On-device model:** Gemma 4 E2B instruction-tuned LiteRT-LM bundle, downloaded from Hugging Face on first launch and stored in private app storage (not Gemini Nano/AICore)
**User:** Personal use (single user); BYOK model if ever shared

## 1. Overview

A personal mobile app that recommends wine (and optionally cheese) pairings through a conversational interface. Suggestions come from an on-device LLM, escalating to a cloud LLM for complex or high-stakes queries. Suggestions may be compared against a static, user-selected Kaggle wine-reviews dataset, with an optional web-search verification step on the matched winery. Gemma does not depend on Kaggle to produce an AI recommendation or populate the AI profile; the dataset is a separate comparative source.

## 2. MVP Scope

Five screens:
1. Splash Screen
2. Main Conversation Screen
3. Stage Show
4. History
5. Favorites

## 3. Out of Scope (this MVP)

- Suggestions / discovery feed (a separate curated/"surprise me" feed — distinct from History, which is your own past suggestions)
- Wine bottle imagery (typography carries the visual weight instead)
- Cloud sync / multi-device support
- Location and price lookup (Google Places) — discussed earlier in the project, not included in these five screens. **Flagging this explicitly since it was part of earlier scoping — confirm this is an intentional deferral, not an oversight.**
- Analytics backend beyond local, on-device logging

## 4. Shared Data Schema

Used by both the AI suggestion and the Kaggle "from reviewers" match, so the two are directly comparable without prose interpretation:

```
name
winery
variety
region
body          (light | medium | full | Unknown)
tannin        (low | medium | high | Unknown)
acidity       (low | medium | high | Unknown)
flavor_notes  (2-4 short tags)
rating        (Unknown if source has none)
confidence    (AI only, integer 0-100 self-assessment; not verified accuracy)
```

Unrecognized/unextractable fields render as "Unknown" rather than guessed. `Unknown` is styled in muted/secondary text, visually distinct from resolved values.

For category-level requests such as "Malbec," the AI may use learned knowledge to provide typical variety characteristics even when no exact bottle is identified. Bottle-specific winery, vintage, critic rating, or provenance claims remain `Unknown` unless the model identifies them with sufficient confidence or a separate source supplies them.

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
- **AC3:** Given the on-device or cloud model generates a response, when phrased, then it uses casual, personable language (e.g. "This could work well — a Malbec would bring some dark fruit into it") rather than a structured field:value breakdown. The model also returns a hidden structured profile for Stage Show; this machine-readable payload is removed before the response is rendered in Chat.

### Data & Content
- **AC4:** Given a query is assessed as high-stakes or complex per the routing logic, when this is detected, then the query is escalated to the cloud LLM. The response stays in the same casual tone; escalation is not called out with a visible badge in the thread.
- **AC5:** Given cheese is not requested, when a response is generated, then no cheese pairing is included by default.
  - **AC5a:** Given the user explicitly requests a cheese pairing, then a cheese suggestion is appended in the same casual tone.
- **AC6:** Given a suggestion is displayed, when the user taps the suggestion itself (no separate button), then the app navigates to Stage Show with that wine's data. This is a view action only — favoriting is a separate, deliberate action performed on Stage Show (Section 7) or from History (Section 8), not an automatic side effect of viewing.
  - **AC6a:** Given a suggestion matches a wine already in Favorites, when it appears in the thread — whether re-suggested in the same session or a new one — then an annotation is shown alongside it (the personal rating, or a "Favorited" mark if unrated) with a tap-through link to that favorite's full record, including the user's notes.

### Error Handling
- **AC7:** Given the cloud LLM call fails (no network, invalid API key, etc.), when this occurs, then an inline error is shown in the thread with a retry option, in the same casual tone. No fabricated content is displayed in place of a failed call.

### Empty States
- **AC8:** Given no query has been submitted yet, when the screen first loads, then an empty state invites the first query via input placeholder text. No fabricated example results are shown.

### Eventing
- **AC9:** Given a query is submitted, then log locally: query text length, routing decision (on-device/cloud), whether a Kaggle match was found, and the verification result — even though none of this surfaces in the thread itself, it's needed for Stage Show's toggle. Personal-use MVP — local logging only, no analytics backend.

**Open assumptions:**
- Exact routing heuristic for cloud escalation (word count? explicit constraint count? on-device confidence signal?) is not yet defined — needed before this can be built.
- Assumed cheese pairing is requested via a dedicated action, not by re-parsing free text for intent — confirm this matches your expectation.
- Whether a small visual affordance (e.g. a chevron) signals a suggestion is tappable, or the whole message bubble is implicitly tappable with no visible cue.

---

## 7. Stage Show

**Purpose:** Full-screen, distraction-free showcase of a single wine. Deliberate and factual in tone — the opposite register from Main Conversation's casual suggestions.

### Navigation & Display
- **AC1:** Given the user navigates by tapping a suggestion (Section 6, AC6), from a Favorites entry, or from a History entry (Section 8, AC5), when Stage Show opens, then it displays wine name, origin, and key traits in large typography, with no persistent nav chrome.
- **AC2:** Given no bottle imagery is used in MVP scope, when Stage Show renders, then typography carries the full visual weight — no image or image placeholder is shown.
- **AC3:** Given a Kaggle dataset match exists for this wine's variety/region, when Stage Show renders, then a toggle ("AI" / "Kaggle") is shown, letting the user switch which source's data is displayed.
  - **AC3a:** Given no Kaggle match exists, when Stage Show renders, then no toggle is shown at all — the screen displays only the AI-sourced data, with no empty second state to switch to.

### Data & Content — Key for Wine Description
- **AC4:** Given the "AI" toggle position is active, when Stage Show renders, then the shared schema fields display sourced from the AI's suggestion (extracted from its response, even though that response was shown casually in the thread):
  - `variety`
  - `region`
  - `body` (light | medium | full | Unknown)
  - `tannin` (low | medium | high | Unknown)
  - `acidity` (low | medium | high | Unknown)
  - `flavor_notes`
  - `rating` (Unknown if the source has none)
- **AC4a:** Given the "Kaggle" toggle position is active, when Stage Show renders, then the same schema fields display, sourced from the matched reviewer entry instead.
- **AC4b:** Given the AI profile contains a self-assessed confidence value, when Stage Show renders, then it displays as "AI confidence" with copy clarifying that it is a model estimate, not verified accuracy.
- **AC4c:** Given no Kaggle comparison exists, when Stage Show renders, then Gemma still populates AI fields from learned knowledge where supportable; absence of Kaggle is not a reason to make AI fields `Unknown`.
- **AC5:** Given a field value is Unknown on either toggle position, when displayed, then it renders in muted/secondary text, visually distinct from resolved values.
- **AC6:** Given both an AI suggestion and a Kaggle match exist, when Stage Show renders (regardless of which toggle position is active), then an agreement indicator is shown near the toggle ("Similar pick" / "Different take") based on variety + region overlap between the two.
- **AC7:** Given the "Kaggle" toggle is active and the matched winery has been verified via web search, when this is the case, then a verified indicator displays near the winery name.
  - **AC7a:** Given verification is inconclusive, absent, or failed, then no badge is shown either way — no false claim in either direction.
- **AC8:** Given the wine has a cheese pairing attached, when Stage Show renders, then the pairing displays as a secondary section below the wine details, not as the primary focus, and independent of which toggle position is active.

### Actions
- **AC9:** Given Wine Profile is open, when it renders, then a `Suggested pairing` field and its concise content are visible upfront with the other profile details; no pairing action button is shown.
- **AC10:** Given Wine Profile is open and the wine is not yet favorited, when the user taps the outlined heart beside AI Confidence, then the heart fills and the wine is saved to Favorites (Section 9), with rating and notes optional at that point. No separate favorites action button is shown.
  - **AC10a:** Given the wine is already favorited when Stage Show opens (e.g. reached via History for something previously favorited), when this is the case, then the "Add to favorites" action is replaced by the existing rating/notes controls, editing that same record directly.
  - **AC10b:** Given a rating or note is set or edited on this screen, when saved, then it updates the same persisted record shown on Favorites.

**Open assumptions:**
- Whether the agreement indicator (AC6) should influence which toggle position is shown by default (e.g. default to Kaggle when it agrees, since it's the more "verifiable" source) — currently unassumed; suggest defaulting to whichever the user tapped from (always AI, since that's the suggestion tapped in the thread).

---

## 8. History

**Purpose:** Browse past suggestions across sessions, grouped by date. The way back into anything you saw before, whether or not you favorited it.

### Navigation & Display
- **AC1:** Given at least one session (past or current) contains suggestions, when the History tab is opened, then suggestions are grouped under date headers (e.g. "Today," "Yesterday," or a calendar date), most recent group first.
- **AC2:** Given the current session has active suggestions, when History is opened mid-session, then those suggestions also appear, grouped under today's date — consistent with the in-session persistence in Section 6, AC2b.

### Data & Content
- **AC3:** Given a suggestion entry in History, when displayed, then it shows the wine name and `Your Request:` followed by useful keywords extracted locally from the user's original prompt, such as country, region, variety, color, body, acidity, tannin, or flavor. Attributes not stated by the user are not added. AI conversation text and the full factual schema are not shown; the schema stays on Stage Show.
- **AC4:** Given a suggestion in History matches a wine already in Favorites, when displayed, then an annotation (rating, or a "Favorited" mark if unrated) is shown with a tap-through link to that favorite's full record and notes — the same behavior as Section 6, AC6a.
- **AC5:** Given the user taps a History entry, when tapped, then the app navigates to Stage Show with that wine's data, identical to tapping a live suggestion.

### Actions
- **AC6:** Given a History entry is not yet favorited, when the user favorites it directly from History (without necessarily opening Stage Show first), then it is added to Favorites the same way as favoriting from Stage Show (Section 7, AC10).
- **AC6a:** Given History contains entries, when the user taps Select, then every entry displays an empty checkbox and the user can select one or multiple entries. Delete remains disabled with no selection and removes all selected entries from local History when enabled and tapped.

### Data Synchronization
- **AC7:** Given this is a single-device personal MVP, when suggestions are logged, then History persists in local on-device storage only. No cloud sync.

### Empty States
- **AC8:** Given no suggestions have been made yet, when History is opened, then its content area remains blank beneath the shared header.

**Open assumptions:**
- How far back History retains suggestions — indefinitely, or with a rolling cutoff (e.g. 90 days)? Not yet defined, and matters for on-device storage growth since there's no server-side cap in this MVP.
- Date-grouping granularity — assumed per-day for now; per-session-within-a-day is an alternative if a single day produces many unrelated suggestions.

---

## 9. Favorites

**Purpose:** Saved wines with an optional personal rating and notes.

### Navigation & Display
- **AC1:** Given the user taps "Add to favorites" on Stage Show (Section 7, AC10) or favorites directly from a History entry (Section 8, AC6), when this happens, then the wine is added to Favorites, with rating and notes optional at that point. Simply viewing a suggestion or opening Stage Show does not, by itself, favorite anything.
- **AC2:** Given Favorites contains at least one entry, when the Favorites screen opens, then entries are listed. **Sort order not yet defined — suggest most-recently-saved first, pending confirmation.**

### Data & Content
- **AC3:** Given a favorited wine, when viewed in the list or its detail view, then the shared schema fields display alongside any personal rating and notes.
- **AC4:** Given the user opens a favorited wine's detail, when they interact with the rating control, then they can set a rating from 1-10 (integer values).
  - **AC4a:** Given no rating has been set, when displayed, then the rating field shows as unrated — visually distinct both from a set 1-10 value and from the schema's own "Unknown" label (this is the user's own optional input, not a missing-data field).
- **AC5:** Given the user adds or edits a personal note, when saved, then the note persists with that favorite entry across sessions.

### Data Synchronization
- **AC6:** Given this is a single-device personal MVP, when data is saved, then Favorites persist in local on-device storage only. No cloud sync in MVP.

### Error Handling
- **AC7:** Given a local storage write fails, when this occurs, then an inline error is shown and the save action does not silently fail.

### Empty States
- **AC8:** Given no favorites exist yet, when the Favorites screen opens, then an empty state invites favoriting a wine from Stage Show or History.

**Open assumptions:**
- Sort order for the Favorites list — not yet defined.
- Whether removing a favorite requires a confirmation step — assumed yes, pending your input.

---

## 10. Open Assumptions & Unresolved Decisions (Full List)

Surfaced here for validation before development starts:

1. Splash: maximum acceptable load duration before "taking longer than usual" messaging.
2. Splash model progress is resolved: network download uses byte percentage, while local checking and SHA-256 verification use indeterminate progress.
3. Main Conversation: exact routing heuristic for on-device → cloud escalation.
4. Main Conversation: cheese pairing assumed to be a dedicated action, not free-text intent parsing.
5. Main Conversation: whether a tappable suggestion needs a visible affordance (chevron, etc.) or is implicitly tappable.
6. Stage Show: default toggle position when both AI and Kaggle data exist — currently assumed to default to whichever the user tapped (always AI).
7. History: retention window — indefinite vs. a rolling cutoff — not yet defined; affects on-device storage growth over time.
8. History: date-grouping granularity — assumed per-day.
9. Favorites: sort order for the list.
10. Favorites: confirmation step assumed required before removing a saved wine.
11. **Scope confirmation:** location/price lookup (Google Places), discussed earlier in this project, is not part of these five MVP screens — confirm this is an intentional deferral.
12. Final system prompt and confidence-calibration policy. The current structured-output instruction is implementation scaffolding and has not been approved as the final product prompt.

---

## 11. Implementation Status

Implemented on native Android with Kotlin and Jetpack Compose:

- Splash branding, authenticated resumable Gemma E2B download, byte progress, SHA-256 verification, three automatic retries, and manual retry state
- Offline LiteRT-LM conversation inference after model installation
- Shared Chat/History/Favorites header with left-side page title and right-aligned Uncork branding, Chat empty state, conversation thread, input composer, dark status-bar treatment, and labeled bottom navigation
- 16sp user and AI message text, Markdown-style `**bold**` rendering, 5% black AI background wash, and 1dp AI rule at 50% opacity
- Full-screen Stage Show navigation and layout, structured AI profile parsing, pairing action, favorite state, and 10-dot rating interaction
- AI-confidence display at 12sp with an explicit accuracy disclaimer
- Persistent on-device History storage, date-grouped History list, Chat/History tab navigation, preserved in-session Chat state, and Stage Show entry navigation

Not yet complete:

- Final product-owned system prompt
- Frank Ruhl Libre font bundling
- AI/Kaggle comparison interface, dataset import, matching, and comparison pipeline; current conditional UI code is only an unvalidated scaffold
- Cloud routing and web verification
- Persistent Favorites, ratings, and notes
- Real model-generated cheese pairing on Stage Show; the current Stage Show result is placeholder copy
- Physical-device execution of the Stage Show instrumentation tests and final responsive visual QA
