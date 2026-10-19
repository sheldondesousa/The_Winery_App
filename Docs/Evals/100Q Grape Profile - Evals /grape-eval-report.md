# Grape profile eval: 3 rounds of 100 questions

Same 100 questions in each round: 50 factual, 25 false claims, 25 unsupported. Every answer was checked against the facts Kotlin supplied. Gemma writes the answer unless Kotlin handles it directly.

## Result in one paragraph

Gemma answers or declines correctly almost every time (99%, 100%, 100%) and is fully correct on 92, 96 and 97 of 100 answers. Its remaining mistakes are small: it sometimes invents a grape name (five times, always "Cabernet Sauvignon Blanc"), puts a region in the wrong place once, or hedges on a comparison the facts allow. Round 3 is the best, but the same questions fail in different rounds, so one run can miss a problem.

## Grading marker (frozen)

| Grade | Marker |
|---|---|
| 2 | All information is correct. |
| 1 | Partly correct. Some information is incorrect, invented, or missed. |
| 0 | Most or all of the answer is incorrect, invented, or missed. |

Only facts Kotlin actually states count. A fact that is merely implied is not required.

## Results by round

| Measure | Round 1 | Round 2 | Round 3 |
|---|---|---|---|
| **Decision** (answer vs decline): accuracy | 99% | 100% | 100% |
| Decision precision / recall / F1 | 100 / 98.7 / 99.3 | 100 / 100 / 100 | 100 / 100 / 100 |
| **Answers fully correct** (of 100) | 92 (85–96%) | 96 (90–98%) | 97 (92–99%) |
| Weighted score (partial = half) | 95.5% | 97.5% | 98.5% |
| Gemma-written answers fully correct | 72 of 79 | 71 of 75 | 72 of 75 |
| Grade 1 (partial) / grade 0 | 7 / 1 | 3 / 1 | 3 / 0 |
| **Claim precision** (unsupported sentences) | 97.0% (6 of 198) | 97.0% (6 of 203) | 98.7% (3 of 237) |
| **Claim recall** (required facts missed) | 95.9% (5 of 122) | 96.7% (4 of 122) | 100% (0 of 122) |
| **Claim F1** | 96.4% | 96.9% | 99.4% |
| Answers with no unsupported content (of 75) | 70 | 70 | 72 |
| False claims corrected explicitly (of 25) | 14 | 14 | 16 |
| Longest answer (limit 6 sentences) | 4 | 4 | 4 |

Intervals are 95% Wilson ranges. The 100 questions share templates, so treat the intervals as optimistic.

## Consistency across rounds

- 89 questions were fully correct in all 3 rounds.
- 7 were fully correct in 2 of 3 rounds, and 4 in 1 of 3.
- No question was never fully correct.

Answers are worded differently every round (almost none are identical), so a single run can miss or catch these.

## Every answer that was not fully correct

| # | What happened | R1 | R2 | R3 |
|---|---|---|---|---|
| 004 | Syrah vs Shiraz: R1 said different grapes; R2 implied they are different. R3 is OK because Kotlin never states they are the same grape | 1 | 1 | 2 |
| 005 | Vermentino vs Arinto acidity: R1 got both levels but said it could not compare; R2 called Vermentino "very high" | 1 | 0 | 2 |
| 020 | Garnacha false claim: R1 returned a Kotlin winery list instead of an answer | 0 | 2 | 2 |
| 021 | Merlot body: R1 and R2 invented "Cabernet Sauvignon Blanc" | 1 | 1 | 2 |
| 036 | Merlot acidity: R2 invented "Cabernet Sauvignon Blanc" | 2 | 1 | 2 |
| 051 | Merlot tannin: R1 and R3 invented "Cabernet Sauvignon Blanc" | 1 | 2 | 1 |
| 076 | "zenvaril" (unknown): R1 said it can only talk about wine instead of saying it has no information | 1 | 2 | 2 |
| 077 | "molvessa" (unknown): R1 treated it as the bottle and answered about Merlot | 1 | 2 | 2 |
| 078 | "ravnexia" (unknown): R1 treated it as the bottle and answered about Merlot | 1 | 2 | 2 |
| 083 | Cabernet Sauvignon light-bodied claim: R3 contains a garbled word, "blackcurrantena" (not human-checked) | 2 | 2 | 1 |
| 094 | Mourvèdre light-bodied claim: R3 put Bandol in Spain (the facts say Provence) | 2 | 2 | 1 |

## Who owns the mistakes

Gemma is graded only on facts Kotlin supplied. Every required fact in the v3 questions was in the supplied context, so no Gemma score is lowered by an unpopulated field.

- **Kotlin:** the winery list in ASK3-020 (round 1), and the five unknown-name questions that reached Gemma in round 1 instead of getting Kotlin's fixed refusal (ASK3-076 to 080). Gemma's replies to ASK3-076, 077 and 078 were partial, but the cause is Kotlin's lookup miss.
- **Gemma:** the invented "Cabernet Sauvignon Blanc", the Syrah vs Shiraz answers that implied different grapes, the Vermentino acidity slips, "blackcurrantena" and "Bandol in Spain".

| Gemma-attributable | Round 1 | Round 2 | Round 3 |
|---|---|---|---|
| Answers Gemma wrote | 74 | 75 | 75 |
| Fully correct | 70 (94.6%) | 71 (94.7%) | 72 (96.0%) |

## What I'd take from this

1. **One repeated Gemma mistake.** "Cabernet Sauvignon Blanc" appears five times, always on Merlot questions where Cabernet Sauvignon is the comparison. That is a pattern, not noise. Fix it in the prompt (use grape names exactly as written in the facts) or check answers for names that aren't in the facts.
2. **Unsupported questions are handled by Kotlin, by design.** In R2 and R3, Kotlin answered all 25 with its fixed refusal, so the app declined every time. Gemma only sees an unknown name if Kotlin's lookup misses it, which happened for 5 questions in R1. Keep a few awkward phrasings (lowercase names, odd casing) in the set so that this fallback path stays tested.
3. **The Syrah vs Shiraz question needs a data fix.** The expected answer says "same grape", but the facts only imply it. Add one explicit sentence to the profile, or reword the required fact.
4. **Kotlin bugs showed up in R1 only.** The winery-list dispatch error (020) and unknown names reaching Gemma did not recur in R2 and R3.
5. **Gemma corrects every false claim with the right facts, but only 14 to 16 of 25 explicitly disagree.** The prompt already asks for this: "politely disagree and provide an answer", "Do not praise or agree with an inconsistent claim", and its example answer starts "Not quite." It also says the example's wording is not required, so a reply that only states the right facts is allowed by the wording but doesn't do the "disagree" part. Decide whether an implicit correction is acceptable. If not, tighten the instruction, for example "begin by saying the claim is not right".
6. **Length and tone are fine.** Every answer is within 6 sentences (longest 4). Tone was read, not scored, and nothing impolite was seen.

## How far to trust the numbers

- **Human check:** a reviewer blindly graded 30 answers (20 I had graded fully correct, 10 not). We agree on 30 of 30 for correct vs not fully correct, and on 29 of 30 for the exact 0/1/2 grade (kappa 0.93). The sample deliberately contained about a third errors, and 30 is a small sample.
- **Single grader:** the other 270 grades are my reading of each answer against its context, with no second review.
- **Not independent runs:** the same 100 questions ran three times, and the questions share templates.
- **Claim metrics** are counted per sentence over the 75 answerable questions (Kotlin refusals excluded), not per individual claim.
- **Round 3's 100% recall** partly reflects the decision to treat ASK3-004 R3 as correct.
- **Prompt version:** the prompt file (`wine_discussion_instruction.txt`) was last changed on Oct 18 at 16:47 and now says "1 to 6 short sentences". Round 2's results file is dated 16:38 and round 3's 18:50, and round 3 has 22 four-sentence answers against 1 to 2 in rounds 1 and 2. So round 3 very likely ran on the 6-sentence prompt and rounds 1 and 2 on an earlier one. Please confirm, because the round-to-round gains then mix prompt changes with run-to-run variation.

## Suggested next steps

1. Fix the "Cabernet Sauvignon Blanc" pattern and re-run.
2. Fix the Syrah / Shiraz fact or required fact.
3. Keep lowercase and odd-casing unknown names in the unsupported set, so Gemma's fallback after a Kotlin lookup miss stays tested.
4. Have a human check ASK3-083 R3 and a second random sample of about 30.
5. Record the prompt and build version with every round.
