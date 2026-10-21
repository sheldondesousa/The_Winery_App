| | Run 1 | Run 2 |
|---|---|---|
| **Retrieval** | | |
| Context added to the prompt, out of the 285 questions Gemma was expected to write | 100% (285 of 285) | 100% (285 of 285) |
| **Conveyed** | | |
| Answered when an answer was expected | 96.8% (276 of 285) | 100% (285 of 285) |
| Declined when a decline was expected | 93.3% (14 of 15) | 93.3% (14 of 15) |
| **Code responsible** | | |
| Code surfaced incorrect info | 2.5% (7 of 285) | 2.5% (7 of 285) |
| Code error | 6.7% (1 of 15) | 6.7% (1 of 15) |
| Code wrongly declined (reply check, removed before run 2) | 3.2% (9 of 285) | 0% (0 of 285) |
| **Relevancy** | | |
| Answers fully correct, app | 88.3% (265 of 300) | 93.3% (280 of 300) |
| Answers partially correct, app | 5.3% (16) | 3.3% (10) |
| Answers wrong, app | 6.3% (19) | 3.3% (10) |
| Answers fully correct, Gemma | 93.0% (251 of 270) | 95.7% (266 of 278) |
| Answers partially correct, Gemma | 5.6% (15 of 270) | 3.2% (9 of 278) |
| Answers wrong, Gemma | 1.5% (4 of 270) | 1.1% (3 of 278) |
| **Performance - Overall App** | | |
| Claims precision | 96.3% (815 of 846) | 96.9% (819 of 845) |
| Claims recall | 93.1% (543 of 583) | 96.6% (563 of 583) |
| Claims F1 | 94.7% | 96.7% |
| **Performance - Gemma** | | |
| Claims precision | 98.0% (801 of 817) | 97.9% (805 of 822) |
| Claims recall | 97.4% (529 of 543) | 98.4% (549 of 558) |
| Claims F1 | 97.7% | 98.2% |

ASK6-263 (Blauer Portugieser) is now graded: partial (1) in both runs, counted as a code error because Kotlin supplied conflicting sources. The Overall App claims rows (precision, recall, F1) have not been recalculated to include it.

Run 2's Gemma rows treat Sauvignon Gris (ASK6-286) as Kotlin's miss. Run 1 still counts it on Gemma's side.
