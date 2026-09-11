# Uncork — Design Brief

*Working name — placeholder pending a real one. Personal, single-user Android app (Kotlin native), on-device Gemma 4 E2B via LiteRT-LM, escalating to a cloud LLM for complex queries. Reference: `PRD.md`.*

Mockup reference: `wine-app-mockup-minimal.html`

---

## 1. Visual system

**One content typeface, one accent color.** Everything else is spacing, weight, and italics.

```css
:root {
  --bg: #F6F1E7;              /* parchment background */
  --ink: #2B2320;              /* primary text */
  --ink-muted: #9A8F82;        /* secondary/quiet text — Unknown, timestamps, notes */
  --accent: #7A2331;           /* wine red — the ONE accent color */
  --line: rgba(43,35,32,0.10); /* hairline dividers */

  --font-display: 'Frank Ruhl Libre', serif;  /* every headline, body, and bubble */
  --font-ui: sans-serif;                       /* system default — micro-labels only */
}
```

**Accent color rule:** `--accent` means "the personal, user-supplied signal." It's used for exactly three things and nothing else:
1. Your own chat bubbles (text color)
2. The rating-dot fill on Stage Show / Favorites
3. The "favorited" annotation pill

Do not introduce a second accent color. If something new needs to stand out, it earns weight or spacing before it earns color.

**Typography rule:** Frank Ruhl Libre remains the target content typeface. Hierarchy comes from size and weight (500/600 for headings, 400 for body), not font-switching. `--font-ui` (system sans-serif) is reserved for structural UI chrome: nav labels, timestamps, schema field labels (`body`, `tannin`, etc.), and the splash tagline. The current Android build uses the platform serif and sans-serif families because Frank Ruhl Libre has not yet been bundled; this is an implementation gap, not a change to the intended type system.

**No elevated cards or shadows.** Boundaries are hairlines (`--line`) or whitespace. Sanctioned exceptions are the input field's bordered container (2px `--line`) and the subtle flat background washes used to distinguish chat participants.

---

## 2. Global patterns

**Status bar** — persistent across every screen (part of the frame, not any individual screen): time left, signal/wifi/battery right. It is transparent over parchment and uses dark system icons/text for contrast.

**Bottom nav** — three tabs: Chat, History, Favorites. It is flush with the physical bottom of the screen, uses a wine-red background, and has equal-width columns separated by hairline dividers. All icons have visible labels. The selected label is 13sp/700 and unselected labels are 12sp/400; selection uses opacity and weight without a background highlight. Appears on those three screens only.
- **Not** on Splash (nothing to navigate to yet).
- **Not** on Stage Show, per PRD §7 AC1 — that screen is explicitly full-screen with no persistent nav chrome.

**Chat demarcation (Main Conversation)** — three redundant, non-color-only cues so no single signal is asked to do too much work:
1. Alignment — user right, AI left (primary cue; matches universal chat-app convention)
2. Your messages: `--accent` text color, tinted background wash (`rgba(122,35,49,0.08)`, 11px radius) — **this is the current default**
3. AI messages: 1dp `--ink` rule at 50% opacity plus a flat black wash at 5% opacity with an 11dp radius

Current message typography: user text 16sp in `--accent`; AI text 16sp in darkened ink `#27201D`. Inline `**bold**` markers from model output render as bold text without exposing the markers.

Two alternate demarcation modes were explored and are available in the mockup if this needs revisiting: "Rule only" (no tints at all) and "AI tint" (assistant gets a `#D3BB98` filled card instead of the rule; user stays plain accent-colored text — this reframes the metaphor as "your words flow, the app's answer is contained," which pairs well with Stage Show's contained/factual register).

**Keyboard interaction** — tapping the input focuses a real text field; the on-screen keyboard is a bottom sheet that slides up **over** the tab bar (covers it, doesn't push it out of layout). A small chevron at the sheet's bottom edge closes it explicitly; tapping anywhere outside it (thread, etc.) closes it too, via standard blur behavior. Hand-off note for Claude Code: this maps directly to `adjustResize`/`WindowInsets` handling with `imeOptions="actionSend"` on the real `EditText` — see §5.

---

## 3. Screen specs

### Splash
- Centered wordmark "Uncork" with tagline "ai sommelier" directly beneath it in `--accent`, sans-serif, lowercase, letter-spaced.
- First launch: masked Hugging Face token entry followed by a thin byte-progress bar and percentage while the 2.58 GB model downloads. Checking and SHA-256 verification use the indeterminate variant.
- Error (after 3 failed retries): copy states the failure directly ("Model failed to load three times. Check your device storage and try again.") rather than a generic message, with a text-only Retry action.

### Main Conversation
- Persistent bordered input at the bottom, bottom nav below it (covered by the keyboard sheet when active).
- Shared header places the page icon and 24sp title ("Chat") on the left and the right-aligned "Uncork" wordmark with "AI SOMMELIER" beneath it on the same horizontal line.
- The conversation content area is intentionally blank before the first message; no invitation text or empty-state artwork is currently shown. The input placeholder reads "Ask me about wine" at 17sp italic.
- States: empty (centered italic invitation, no fabricated example results), active (thread), error (inline error bubble, casual tone maintained per PRD §6 AC7).
- Structured wine-profile JSON is internal UI data. Whether Gemma wraps it in the requested profile markers, a fenced JSON block, or emits only the profile, it is parsed for Stage Show and never rendered in the visible conversation.
- The in-progress state reads "Thinking…" at 14sp italic. "Researching…" is reserved for a future route that actually performs external or dataset retrieval.
- AI suggestion bubbles carry a trailing chevron (›) as a tap affordance — resolves PRD open assumption #5 in favor of a visible cue over silent tappability.
- Favorited wines get an inline annotation (rating or "Favorited") with tap-through, per PRD §6 AC6a.
- Sending a message (via the simulated keyboard's Send key) appends it to the thread as a new user bubble.

### Stage Show
- No persistent bottom nav chrome (PRD §7 AC1). The top header uses a back arrow with the page title "Wine Selection" on the left and the shared right-aligned Uncork / AI SOMMELIER lockup. Large typographic wine name + origin, no bottle imagery.
- Current sizes: wine name 42sp, region 17sp, schema values 15sp, schema micro-labels 10sp, cheese-pairing copy 16sp, and AI-confidence line 12sp.
- The planned AI/Kaggle toggle is a plain text switch (not a pill), with an agreement indicator ("similar pick") next to it. This comparison interface has not yet been designed, connected to data, or approved; the current code contains only an unvalidated conditional scaffold.
- AI is the primary source and should populate the shared schema from Gemma's learned knowledge. Kaggle is optional comparative evidence; its absence must not force the AI fields to `Unknown`.
- When present, AI confidence is labeled as a model estimate rather than verified accuracy. It must never be presented as a probability that the facts are correct.
- Short, single-answer schema fields (`variety`, `body`, `tannin`, `acidity`, and `rating`) use a two-column grid with sans-serif micro-labels. Longer content such as `flavor notes` and pairing guidance remains full-width and left-aligned. `Unknown` renders as muted italic — visually distinct from a user's own unset rating ("not yet rated"), which is muted but *not* italic. These two null states must never be styled identically (PRD §4).
- Cheese pairing is a secondary, quieter block below the main schema — never the primary focus (PRD §7 AC8).
- Rating uses a 10-dot strip rather than numeric stepper or stars, matching the integer 1–10 scale without implying half-points.

### History
- Grouped by date header (sans-serif micro-label: "today," "yesterday"). Each entry: wine name, concise user request, and favorited annotation if applicable.
- The shared header places the History icon and 24sp title on the left and the right-aligned Uncork/AI SOMMELIER brand lockup on the same line. Entries use the wine name as the primary serif line, followed by region, `Your Request:` plus locally extracted user-request keywords (for example country, region, variety, color, body, acidity, tannin, and flavor), and a trailing chevron. AI conversation text is not shown in History, and suggestion attributes not present in the user's request are not added as request keywords.
- The empty History content area is intentionally blank for now.
- Select and Delete sit on the same line as the most recent date heading (for example, TODAY), separated by a short vertical hairline. Select—and Cancel while selection mode is open—uses active wine-colored semibold text without a filled background. Select reveals an empty checkbox to the left of every History entry. Delete stays muted and disabled until at least one item is selected, then receives the filled wine-red highlight and removes all selected local records when tapped.
- Sits above the shared bottom nav.

### Favorites
- Sorted most-recently-saved first (resolves PRD open assumption #9).
- Rating badge in `--accent`; unrated entries read "not yet rated" in muted italic — same distinction rule as Stage Show's `Unknown`.
- Sits above the shared bottom nav.

---

## 4. Decisions made this pass (flagging for sign-off)

These resolve open assumptions from the PRD or were made unprompted while building the mockup — flag any you'd override:

| Area | Decision | PRD reference |
|---|---|---|
| Suggestion tap affordance | Trailing chevron (›), not silent tap | §6, open assumption #5 |
| Splash error copy | Shows the "3 retries failed" message directly | §5, open assumption |
| Favorites sort order | Most-recently-saved first | §9, open assumption #9 |
| Rating control | 10-dot strip, not stars/stepper | §9 AC4 |
| Unknown vs. unrated | Different styling (italic-muted vs. plain-muted) so they're never confused | §4 |
| Chat demarcation | Alignment + accent tint (user) + rule (AI) — three redundant cues, none color-only | new |
| Bottom nav scope | Conversation/History/Favorites only; excluded from Splash and Stage Show | new, respects §7 AC1 |
| Active tab | No filled highlight; full-opacity icon/label plus heavier 13sp label | implementation update |
| AI response surface | 5% black wash, 11dp radius, and 1dp left rule at 50% opacity | implementation update |
| AI confidence | Self-assessed model estimate with explicit accuracy disclaimer | §7 AC4 |

---

## 5. Handoff notes for implementation

- **Keyboard behavior**: use `adjustResize` (or `WindowInsets` in Compose) so the input bar rises above the real IME and the thread compresses behind it. Set `imeOptions="actionSend"` so the keyboard's own return key sends directly.
- **Contrast**: the wine-red accent (`#7A2331`) against the parchment background (`#F6F1E7`) passes WCAG AA for body text (~8.8:1) — safe to use as the primary text color for user bubbles, not just for accents.
- **Accessibility**: alignment and color cues in the chat thread are sighted-only. Real implementation needs an accessible sender label (e.g. "You said" / "Assistant said") for screen readers even though it's never shown visually.
- **Model output contract**: the current implementation requests conversational prose plus a hidden `[WINE_PROFILE]...[/WINE_PROFILE]` JSON object. The JSON is parsed into Stage Show and removed from visible chat. This system instruction is provisional; the product owner plans to supply the final prompt later.

---

## 6. Still open (not yet decided)

- Whether "Suggest a pairing" needs a distinct visual state for *before* a pairing exists vs. the already-suggested state shown in the mockup.
- Navigation model: whether the bottom nav's Conversation/History/Favorites tabs are the sole way to move between those three, or whether Stage Show and other drill-ins should also get an explicit back control beyond the current back chevron.
- The PRD's own flagged scope question: whether location/price lookup (Google Places) is intentionally deferred from this MVP or was dropped by oversight (PRD §10, item 11).
- Final Frank Ruhl Libre font bundling and device-level visual validation.
- Confidence calibration: current values are model self-assessments, not empirically measured accuracy scores.
