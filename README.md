# Uncork

Uncork is a personal AI sommelier for discovering wine and optional cheese pairings through a conversational experience. It prioritizes private, on-device inference and can escalate complex queries to a cloud model when needed.

> [!IMPORTANT]
> Uncork must be built as a native Android application using Kotlin. It is not a cross-platform, hybrid, or web-based app.

## Product overview

The app recommends wines in a casual conversational tone, then presents each recommendation in a focused detail view with structured information such as variety, region, body, tannin, acidity, flavor notes, and rating.

AI recommendations can be compared with matching entries from a static wine-review dataset. Where available, winery information may also be corroborated through web search. Missing or unverified data is displayed as unknown rather than inferred.

The MVP is intended for personal, single-user use and stores history, favorites, ratings, notes, and event logs locally on the device.

## MVP screens

1. **Splash** — displays the Uncork identity while loading and validating the on-device model.
2. **Conversation** — accepts natural-language requests and returns personable wine suggestions.
3. **Stage Show** — presents one wine in a full-screen, typography-led detail view and supports AI/Kaggle comparison.
4. **History** — groups previous suggestions by date and provides access to their detail views.
5. **Favorites** — stores selected wines with an optional personal rating and notes.

## Core capabilities

- Conversational wine recommendations
- Optional cheese-pairing suggestions
- On-device LLM inference with cloud escalation for complex queries
- Structured wine details with explicit unknown states
- Comparison against a static Kaggle wine-review dataset
- Optional winery verification through web search
- Local conversation history and favorites
- Personal ratings on a 1–10 scale and saved notes
- Offline-first, single-device storage

## Android implementation

- **Platform:** Android only
- **Language:** Kotlin
- **UI:** Native Android UI, preferably Jetpack Compose
- **On-device AI:** Gemma 4 E2B through Google LiteRT-LM. The pinned 2.58 GB model is downloaded from Hugging Face on first launch, verified with SHA-256, and then runs from private app storage without a network connection.
- **Cloud AI:** Used only when routing determines a request is too complex or sensitive for the on-device model
- **Persistence:** Local on-device storage; no cloud sync in the MVP
- **Keyboard behavior:** Android IME action sends messages, with proper WindowInsets/resize handling
- **Accessibility:** Screen-reader sender labels and interaction cues that do not depend on color alone

## Visual direction

Uncork uses a restrained, editorial visual system led by typography:

- Parchment background: `#F6F1E7`
- Primary ink: `#2B2320`
- Muted ink: `#9A8F82`
- Wine-red accent: `#7A2331`
- Frank Ruhl Libre for content and system sans-serif for UI micro-labels
- Hairline dividers and whitespace instead of cards and shadows
- No bottle imagery in the MVP

The wine-red accent is reserved for user-supplied signals: user chat messages, personal rating dots, and favorite annotations.

## MVP exclusions

- Cross-platform or hybrid clients
- Discovery or “surprise me” feed
- Bottle imagery
- Cloud sync and multi-device support
- Location and price lookup
- Remote analytics backend

## Documentation

- [Product requirements](Docs/PRD.md)
- [Design brief](Docs/Design-Brief.md)

## Status

The project is in active MVP development. The native shell, Splash, first-launch model acquisition, offline LiteRT-LM inference, and initial Chat interface are implemented. Cloud-routing criteria, history retention, and final drill-in navigation remain open.

## First launch

The first time Uncork opens, enter a Hugging Face read token. The token is sent only as an authorization header for the model download and is never persisted by the app. Interrupted downloads are retained as a partial file and resumed automatically. Keep Uncork open until download and verification complete.

After successful verification, later launches skip the download flow and open Chat directly. Prompts and responses are processed by LiteRT-LM on the device, so chat remains available offline.
