# Gemma sommelier eval design

## Goal
Find out whether Gemma answers only from your data, declines when data is missing, and gives complete, correct answers.

## 1. Test set (100 questions)
- **Answerable (50):** the data has the answer. Include sparse-data grapes on purpose.
- **False claims (25):** the user says something wrong and the data corrects it.
- **Unsupported (25):** the data has no answer. Include unfamiliar names, lowercase names, and tempting questions the model might know from training.
- **Vary the wording and casing across the 100** instead of repeating one template, so you test the model, not your templates.
- **Keep a frozen version** of the set and never tune the prompt on it. Mark about 20 of the 100 as a final-check group that you only run once, at the end.

## 2. What to record per question
The question, the context Kotlin supplied, Gemma's answer, the route (Gemma or Kotlin), and the prompt version.

## 3. How to score (three layers)
1. **Decision:** did it answer or decline correctly? Report accuracy, precision, recall and F1.
2. **Answer grade:** correct, partially correct, or wrong. This is your headline number.
3. **Rubric dimensions, each scored 0, 1 or 2 and reported separately:**

| Dimension | Question it answers | How to measure |
|---|---|---|
| Factual consistency | Does the answer match what Kotlin supplied, with nothing invented and nothing relevant left out? | **False positive:** a fact in the answer that is not in Kotlin's response (invented, added or contradicting). **False negative:** a relevant fact in Kotlin's response that the answer didn't use. Precision = supported statements ÷ all statements. Recall = relevant supplied facts used ÷ relevant supplied facts. F1 combines them. |
| Completeness | Did it actually address what was asked: answer the question, correct the false claim, or clearly say the information isn't available? | LLM judge, 0-2. Judged against the supplied context only, never outside knowledge. |
| Verbosity | Is it within 6 sentences? | Automatic sentence-count check. 2 if within the limit, 0 if over. |
| Tone | Is it friendly and respectful, including when disagreeing with a false claim? | LLM judge, plus a human spot-check. |

Count a false negative only for facts relevant to the question. Kotlin's response may hold many facts, and Gemma shouldn't list them all. A short answer that is fully correct has high precision and lower recall.

**Context check (Kotlin's job, not Gemma's):** before scoring, mark each question "context sufficient" or "context insufficient". If Kotlin didn't supply what the question needs, the right answer is to say the information isn't available, and any gap is counted as a Kotlin/retrieval failure, not a Gemma completeness failure.

Factual consistency and Completeness decide whether the answer is good. Verbosity and Tone are guardrails: report them, but don't blend all four into one total, because Tone and Verbosity will sit near 100% and hide the real failures.

## 4. Judging
- Use an LLM judge for scale.
- Have a human blind-check about 30 random answers. Add the disputed ones.
- Freeze the grading rules **before** running.

## 5. Run it properly
- Run each question 3 times, because small models vary.
- Test every prompt change on the same frozen set.
- Change one thing at a time, so you know what caused any difference.

## 6. Report
- Results overall and per category.
- Gemma errors vs Kotlin errors, shown separately.
- A margin of error on every percentage.

## 7. Ship rules (set these before you run)
For example:
- Zero invented facts on unsupported questions.
- At least 95% correct overall.
- No category below 90%.

If it misses, fix and re-run on the same frozen set.
