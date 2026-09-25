# Gemma performance optimization summary — 25 September 2026

## Outcome

Chat's on-device Gemma recommendation workflow fell from a historical average of **48.13 seconds** to **6.28 seconds** for three usable recommendation cards on the same GPU backend. The third card now appears at **6.19 seconds**, compared with approximately **42.59–50.34 seconds** in the original successful GPU runs.

This is an **87% reduction** in initial recommendation time. The improvement came primarily from asking Gemma to write substantially less text, rather than from database, model-loading, prompt-preparation, parsing, or UI changes.

| Measurement | Historical GPU baseline | Current GPU result | Change |
| --- | ---: | ---: | ---: |
| First output | 1.89–2.31s | 1.03s | Faster startup |
| First card ready | 16.11–18.22s | 2.96s | About 82% faster |
| Third card ready | 42.59–50.34s | 6.19s | About 86% faster |
| Total initial request | 43.42–51.32s | 6.28s | About 87% faster than the 48.13s average |
| Generated characters | 1,453–1,666 | 289 | About 81% less output |

## What the diagnostics showed

The first detailed measurements separated engine readiness, prompt preparation, conversation creation, time to first output, writing, parsing, and validation. SQLite searches were effectively immediate. The Gemma engine was already loaded, prompt preparation took approximately 0–4 milliseconds, and parsing and validation were also negligible.

Gemma's writing stage consumed roughly **41–49 seconds** in the historical GPU tests. It accounted for nearly the entire wait. This established that optimizing Kotlin, SQLite, model loading, or UI rendering would not materially improve the workflow. Gemma had to produce fewer tokens.

## Changes made

### Progressive card publication

The response stream was parsed while Gemma was still generating. Kotlin published each complete JSON object as soon as it closed, allowing the first and second recommendations to appear before the entire response finished. This improved perceived responsiveness and provided precise first-card, second-card, and third-card timings, although it did not initially reduce Gemma's total writing work.

### Removed unnecessary initial content

The initial card prompt stopped asking Gemma for summaries and other details that were not visible in the collapsed recommendation card. Removing the summary alone reduced one measured set from a historical average of **48.13 seconds** to **25.76 seconds**, although that experiment mixed CPU and GPU runs and therefore could not attribute the full difference to the prompt change.

### Reused user-supplied values

Kotlin became authoritative for preferences the user had already supplied. Gemma no longer needs to repeat fields such as country, province, variety, body, tannin, acidity, sweetness, or wine type when the app already knows them. Kotlin merges those values into each card after generation.

Missing, empty, null, or `None` values are normalized to **Unknown** by Kotlin. This avoids spending generation time writing placeholder values while keeping the UI honest about unavailable information. Existing alias mapping remains in place so valid variations such as `wine_name`, `region`, or `grape` can still be understood without another model call.

### Split cards from details

The largest improvement came from dividing generation into two stages:

1. The initial Chat request produces only the four fields required by the collapsed card: name, country, province, and variety. Fields already supplied by the user may be omitted.
2. When the user opens a Gemma card, the available data renders immediately. Gemma then generates the remaining profile fields for that selected wine only. Each unresolved field displays a small loader until the request completes.

This avoids generating full profiles for three wines when the user may open only one.

### Adopted Find's JSON envelope

The first compact prompt completed in approximately 10 seconds but produced three malformed JSON objects. Chat was then changed to use the same stable response envelope as Find:

```json
{
  "recommendations": [
    {
      "name": "...",
      "country": "...",
      "province": "...",
      "variety": "..."
    }
  ]
}
```

The progressive parser was verified against this structure, including recommendations that omit user-supplied fields. The next run produced three valid cards in **7.95 seconds**, and the latest run reduced that to **6.28 seconds**.

The initial output allowance was also reduced from 1,024 to 384 tokens, providing enough room for three compact cards while limiting unnecessary continuation.

## Current two-stage performance

The latest initial request produced all three usable cards with these cumulative times:

- First card: **2.96 seconds**
- Second card: **4.32 seconds**
- Third card: **6.19 seconds**
- Total initial request: **6.28 seconds**

The latest Stage Show request began with eight pending fields. It waited **0 milliseconds** for the shared engine, spent **8.60 seconds** generating details, and stopped all loaders after **8.68 seconds**. Seven fields were resolved and one remained Unknown.

If a user opens a card, the two model operations currently require approximately **14.95 seconds** in total: 6.28 seconds for all three initial cards and 8.68 seconds for the selected card's details. That is approximately **69% less model time** than the historical 48.13-second average, while presenting the recommendations after only six seconds and rendering known Stage Show data immediately.

## Practical result

The workflow now spends Gemma time in proportion to what the user sees. Three compact choices arrive quickly. Full detail generation happens only for the wine the user selects, and the page remains useful while that work is pending. Persistent diagnostics now record both initial card timing and Stage Show pending-detail timing so future changes can be compared against the current **6.28-second initial** and **8.68-second detail** baselines.
