# Grape profile eval v4: 100 varied questions, 3 runs

50 answerable, 25 false-claim and 25 unsupported questions across 67 grapes. All three runs used the same prompt (hash 487e2e25…) and the same app build. 99 of 100 answers were identical across runs, so the runs repeat rather than add evidence.

## Result in one paragraph

Gemma's own answers are accurate and mostly complete: 92 to 94% fully correct, one questionable sentence in 223, and no invented names. The app as a whole is held back by two Kotlin bugs that account for all 9 zeros. The answer-or-decline decision is 91% for the user: 9 of the 75 answerable questions got no answer. The run log's route check reports 97% because it records a route, not what is shown, so it misses the 6 reply-check refusals. It is not the decision accuracy.

## Grading rules

| Grade | Marker |
|---|---|
| 2 | Covers what the question asked for, with nothing wrong, missed, or invented. |
| 1 | Partly correct. Some information is incorrect, invented, or missed. |
| 0 | Most or all of the answer is incorrect, invented, or missed. |

- **Caveat:** Gemma does not lose a point for leaving out facts Kotlin did not supply.
- **Only what Kotlin surfaced counts.** Gemma is graded on the facts Kotlin supplied. I checked every required fact in all 75 answerable questions, and every one was in the supplied context, so no Gemma score is lowered by an unpopulated field.
- **Kotlin errors are Kotlin's.** Refusals and replies produced by the app are scored to Kotlin, not Gemma.
- **Descriptions have a length limit.** Gemma is limited to 1,000 characters or 6 sentences. For a description question, the five core fields (summary, body, acidity, tannin, aromas) earn a 2, and ageing and origin may be dropped.
- **Field questions don't.** If a question asks about a specific field and Kotlin supplied it, leaving it out is a Gemma error.

## Results

| Measure | Run 1 | Runs 2 and 3 |
|---|---|---|
| **Decision, what the user sees** | **91%** (precision 100%, recall 88%, F1 93.6%) | same |
| Fully correct (2) / partial (1) / zero (0) | 86 / 5 / 9 | 87 / 4 / 9 |
| Weighted score (partial = half) | 88.5% | 89.0% |
| **Gemma-written answers fully correct** | **61 of 66 (92.4%)** | **62 of 66 (93.9%)** |
| Gemma-only claim precision / recall / F1 | 99.6% / 96.5% / 98.0% | 99.6% / 97.6% / 98.6% |
| Whole-app claim precision / recall / F1 | 95.7% / 83.7% / 89.3% | 95.7% / 84.7% / 89.9% |
| Unsupported questions refused correctly | 25 of 25 (by Kotlin) | same |
| Time to first word (median) | 0.40 s | 0.41 to 0.46 s |
| Full reply (median / slowest 5%) | 4.3 s / 7.4 s | 4.4 to 5.7 s / 7.6 to 10.1 s |
| Answers over 6 sentences | 1 (ASK4-013, 7 sentences) | same |

## Confidence intervals

95% Wilson ranges for one set of 100 questions. The three runs give almost identical answers, so they don't tighten the intervals, and the questions share templates, so treat the ranges as optimistic. The Gemma-written and whole-app ranges overlap, so that gap is suggestive rather than proven.

| Measure | Run 1 | 95% interval | Runs 2 and 3 | 95% interval |
|---|---|---|---|---|
| Decision accuracy (what the user sees) | 91 of 100 | 84–95% | 91 of 100 | 84–95% |
| Decision recall | 66 of 75 | 79–94% | 66 of 75 | 79–94% |
| Fully correct, whole app | 86 of 100 | 78–91% | 87 of 100 | 79–92% |
| Fully correct, Gemma-written answers | 61 of 66 | 83–97% | 62 of 66 | 85–98% |
| Unsupported questions refused | 25 of 25 | 87–100% | 25 of 25 | 87–100% |

## By question type (run 1)

| Type | Questions | Fully correct |
|---|---|---|
| Comparison | 10 | 10 |
| Body, acidity, aromas, origin | 15 | 15 |
| Tannin | 4 | 3 (the miss is Kotlin's) |
| False claims | 25 | 24 (the miss is Kotlin's) |
| Unsupported | 25 | 25 |
| Full description | 15 | 9 (4 are Kotlin's) |
| Summary | 3 | 0 (1 Kotlin, 2 partial) |
| Ageing | 3 | 0 (2 Kotlin, 1 partial) |

## What cost points, and who owns them

| Cause | Owner | Questions | Grade |
|---|---|---|---|
| Reply check replaced a good answer with a refusal: ASK4-005, 006, 007, 030, 037, 039 | Kotlin | 6 | 0 |
| Winery list instead of the grape answer: ASK4-015 Carignan, 049 Müller-Thurgau, 064 Kerner | Kotlin | 3 | 0 |
| Description left out core fields Kotlin supplied: ASK4-008 Carménère (no body or acidity); ASK4-001 Barbera, run 1 only | Gemma (omission) | 2 (1 in runs 2 and 3) | 1 |
| Summary only partly covered: ASK4-032 Cabernet Sauvignon, 046 Petit Verdot | Gemma (omission) | 2 | 1 |
| Ageing wording shifts the meaning: ASK4-044 "enjoyed after 8 to 15 years" | Gemma (error) | 1 | 1 |
| Seven sentences, over the limit of six: ASK4-013; the content is correct | Gemma (format) | 1 | 2, flagged |

## What I'd take from this

1. **The reply check refuses good answers (Kotlin).** In the logs the check's context contains only the reminder and the user's question, not the grape facts. Gemma writes "best", which the check treats as unsupported even when the notes say "Best enjoyed in…". The retry gives the same word, and with no grape facts in that context there is no fallback sentence, so the user sees "I'm sorry, I do not have that information." The route is still logged as a normal answer, so route-based metrics miss it. Fix: give the check the same facts Gemma had.
2. **Grape names that match winery names get a winery reply (Kotlin).** Carignan, Müller-Thurgau and Kerner return "Château Carignan is in…". The same kind of dispatch bug appeared in v3 (ASK3-020).
3. **Gemma's content is accurate.** No invented names, and the "Cabernet Sauvignon Blanc" pattern from v3 is gone. The Syrah / Shiraz profile now says they are the same grape, and that question passes.
4. **Gemma's remaining gaps are small.** It skipped body and acidity in one overview (Carménère), gave half a summary twice, shifted the ageing meaning once, and wrote one 7-sentence answer.
5. **Decision metrics should be computed from what is shown, not the route.** Log the output source (Gemma or Kotlin) in the pass/fail check.
6. **Repeats add little here.** Outputs are near-deterministic, so more runs of the same questions will not find new problems. New questions will.

## How far to trust the numbers

- **Single grader:** these grades are Claude's reading of each answer against its context. They have not had the blind human check that v3 had.
- **Judgment calls to confirm:** ASK4-008 at 1, ASK4-032 and 046 at 1 for half a summary, and ASK4-044 at 1.
- **Counts:** claim metrics count sentences and required fields over the 75 answerable questions, not individual claims. For descriptions, the required fields are the five core fields.
- **Code cause:** point 1 comes from the logged context. I have not traced the call site in the code.
