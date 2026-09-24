# Bug Log

**Status:** Living document
**Last updated:** 20 September 2026

Every bug reported during this build session, in the order it came up. Scope is this session's work (web search reliability, the Find guided-selection redesign, and the Q3 taste question) — not a full historical defect list for the app.

Each entry: what was reported, what the actual root cause turned out to be, and what changed to fix it.

---

## 1. Broader web search always returned "I couldn't find a relevant wine for that request"

**Reported:** Asking Chat to run a broader web search consistently failed, even though Brave Search was fully wired up (API key configured, network call implemented).

**User impact:** Every attempt to use the "broader web search" follow-up ended in "I'm sorry, but I couldn't find a relevant wine for that request" — a dead end regardless of the query. The entire web-search fallback was unusable from the user's side, with no visible indication of why (no error, no retry, just a polite non-answer).

**Investigation took several rounds, because there wasn't one cause:**

- **1a. All-or-nothing JSON parsing.** `toWebSuggestions()` parsed Gemma's entire `[WEB_RESULTS]` array inside a single `runCatching` block. If any one wine object in the array was missing a required field, the exception discarded the *whole batch* — including any wines that had parsed correctly. Fixed by parsing each object independently in [GemmaConversationResponder.kt](../app/src/main/java/com/sheldondesousa/uncork/model/GemmaConversationResponder.kt).
- **1b. Overly strict required fields.** Both the parser and the display filter (`hasRequiredCardFields()`) required a wine to have both `name` and `country` resolved before showing it. Relaxed to only require `name`, with `country` and other fields falling back to `"Unknown"` instead of failing the card, in [KaggleConversationResponder.kt](../app/src/main/java/com/sheldondesousa/uncork/model/KaggleConversationResponder.kt).
- **1c. Root cause, found by testing the real Brave API call directly:** Brave itself was working fine — a live test call returned 12 solid, on-topic results every time. The actual problem was downstream: the raw evidence text handed to Gemma (titles + full HTML-laden snippets from all 12 results) measured **14,092 characters**, still containing literal `<strong>` tags and `&#x27;` entities. That is a large, noisy prompt for a small on-device model to turn into clean JSON within its output budget — exactly the kind of input that causes a small model to ramble and never close its JSON array, which (even after fix 1a) still means zero usable results if the top-level array itself doesn't parse.
  - Fixed by stripping HTML tags/entities and capping each snippet to 300 characters, and cutting the Brave fetch from 12 results down to 3, in [BraveWineWebSearchDataSource.kt](../app/src/main/java/com/sheldondesousa/uncork/model/BraveWineWebSearchDataSource.kt). Verified with a real network call: evidence size dropped from 14,092 to 3,454 characters with no HTML remaining.

**Status:** Fixed and confirmed working end-to-end on a physical device — the user reported the full cycle completing with 3 web results.

---

## 2. Typing "Medium" for body preference made Chat repeat the question

**Reported:** After the Q3 taste question was reworded to show "Light, Medium, Full" (dropping the "-Bodied" suffix), typing the single word "Medium" caused the app to apologize and repeat the question instead of matching.

**User impact:** A user who answered with exactly the word the question itself displayed ("Medium") got stuck — the app apologized and re-asked the same question instead of advancing, blocking progress through the guided find-a-wine flow. This was the most likely single-word answer for a user to give, since it's literally one of the three options shown.

**Root cause:** In [ChatFlow.kt](../app/src/main/java/com/sheldondesousa/uncork/model/ChatFlow.kt), the `Medium-Bodied` keyword list only included the two-word phrases `"medium-bodied"` and `"medium bodied"` — unlike `Light-Bodied` and `Full-Bodied`, which both included their bare single-word forms (`"light"`, `"full"`). This asymmetry existed before the question wording changed but was masked by the old question text, which spelled out the full "-Bodied" phrase and nudged people toward typing it in full.

**Fix:** Added the bare word `"medium"` to the `Medium-Bodied` synonym list.

---

## 3. Find page's Acidity bottom sheet was completely empty

**Reported:** Opening the Acidity field's bottom sheet on the Find page showed no options at all, while Body, Tannin, and Sweetness worked correctly.

**User impact:** Tapping the Acidity tile opened a bottom sheet with a title and no selectable rows underneath it — a dead-looking, apparently broken control. Acidity was effectively unusable as a filter on the Find page; a user had no way to search by acidity level at all.

**Root cause:** In `FormField.options()` in [GuidedSelectionScreen.kt](../app/src/main/java/com/sheldondesousa/uncork/ui/guided/GuidedSelectionScreen.kt), a `when` branch grouped `FormField.Acidity` together with `FormField.Country` and `FormField.Province` under `emptyList()`. Country and Province legitimately return an empty static list there (their options come from the database at runtime through a separate code path) — Acidity was mistakenly swept into the same branch. The underlying data (`GuidedOptions.acidity` → `["Soft", "Crisp", "Tart"]`) was never missing; the UI just never asked for it.

**Fix:** Gave `FormField.Acidity` its own branch returning `GuidedOptions.acidity`, matching the pattern already used for Body/Tannin/Sweetness.

---

## 4. Font, row height, and padding inconsistent across Find bottom sheets

**User impact:** Navigating between different Find bottom sheets felt inconsistent and unpolished — Country and Province rows used a noticeably larger font, sat visibly taller with more dead space around the selection dot, and had no dividing lines between entries, while Sweetness/Tannin/Body/Acidity/Type showed a tighter, evenly-spaced list with visible separators. A user moving from one field to the next would notice the UI "shifting" in look and feel for no apparent reason.

**Reported, in three follow-up rounds:**

- **4a.** Font type/size differed between Country/Province rows and the other fields' rows (Country/Province had no explicit text style, defaulting to a larger font than the `bodyMedium` used elsewhere), and vertical padding around each row's dividers differed (`6dp` vs `10dp`).
  **Fix:** Unified both to `bodyMedium` and `10dp` vertical padding with a `48dp` minimum row height everywhere.
- **4b.** After that fix, a discrepancy still remained. Root cause: Country/Province used a real Material3 `RadioButton`, which always reserves a 48dp × 48dp touch target internally regardless of any surrounding sizing — versus the other fields' compact, custom 20dp `Canvas`-drawn indicator. Same visible dot size, very different layout footprint (48dp box vs 20dp box), which pushed the Country/Province row height to ~68dp against ~48dp everywhere else, and widened the gap between the indicator and its label.
  **Fix:** Replaced the Material3 `RadioButton` with a new `RadioDot` composable — the same 20dp `Canvas` box, colors, and stroke width as the other fields' indicator, drawn with radio semantics (outer ring plus a filled inner dot when selected) instead of a checkbox tick.
- **4c.** Country/Province's option rows had no visual separators between them at all, while the redesigned single-column layout for the other fields did.
  **Fix:** Added the same `HorizontalDivider` between every row, including before the first real option (after the "Any country"/"Any province" row).

**Status:** Fixed; user confirmed no further discrepancy after the `RadioDot` change.

---

## 5. Type and the taste characteristics were multi-select while Country/Province were single-select

**Reported:** Country and Province behave correctly — pick one, it's applied, the sheet closes. But Type, Sweetness, Tannin, Body, and Acidity allowed selecting multiple values, required a separate "Done" tap to close, and didn't auto-apply on tap. This inconsistency was flagged as unintended.

**User impact:** The Find form behaved like two different apps stitched together. A user picking a Country expected the sheet to close immediately after one tap (and it did); the same user picking a Body or Tannin preference instead had to tap a value, notice nothing closed, then hunt for a separate "Done" button — and along the way could accidentally leave more than one option checked without realizing it, producing a search filtered on multiple values they didn't intend to combine.

**Root cause:** Not a defect so much as two features built to different specs at different times — Country/Province were single-select by design, the other five fields were built as a multi-select checkbox grid ("select any that apply" was an explicit hint for Type).

**Fix:** Unified all seven fields to single-select behavior: tapping a value replaces any prior selection and immediately closes the sheet; an "Any {field}" row was added to each of the five taste/type fields (matching Country/Province's existing pattern) so a field can still be cleared; the "Done" button was removed entirely. The old multi-select grid composable (`Choices`) was deleted as dead code, along with its now-obsolete Compose UI test, which was rewritten to match the new single-select-and-auto-dismiss flow.
