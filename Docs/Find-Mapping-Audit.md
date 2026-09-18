# Find mapping data audit

Generated from compiled application mappings and queries using `scripts/audit_find_mapping.py`. Database opened read-only.

## Type coverage

| Type | Exact variety values | Matching rows |
|---|---:|---:|
| Red | 29 | 64,542 |
| White | 25 | 32,729 |
| Sparkling | 4 | 3,062 |
| Rosé | 2 | 3,229 |
| Fortified | 4 | 707 |

Mapped 64 of 701 distinct variety values and 104,269 of 119,030 rows. The remaining 637 variety values are **unclassified**, not absent from the database. They remain searchable when Type is unselected. See [unclassified inventory](Find-Unclassified-Varieties.csv).

## Classified column evidence (Tannin/Acidity/Body)

These fields match the precomputed `tannin`/`acidity`/`body` columns directly, not review text.

| Field / option | Matching rows |
|---|---:|
| Tannin/Smooth | 19,477 |
| Tannin/Moderate | 18,856 |
| Tannin/Astringent | 3,105 |
| Acidity/Soft | 11,437 |
| Acidity/Crisp | 25,632 |
| Acidity/Tart | 7,556 |
| Body/Light-Bodied | 29,388 |
| Body/Medium-Bodied | 6,025 |
| Body/Full-Bodied | 33,929 |

## Phrase evidence (Sweetness)

Sweetness has no precomputed column, so it still matches `review_summary` substrings.

| Field / option | Phrase | Matching rows |
|---|---|---:|
| Sweetness/Bone-Dry | bone-dry | 291 |
| Sweetness/Bone-Dry | bone dry | 571 |
| Sweetness/Bone-Dry | crisp and dry | 57 |
| Sweetness/Off-Dry | off-dry | 1,082 |
| Sweetness/Off-Dry | off dry | 501 |
| Sweetness/Off-Dry | semi-sweet | 81 |
| Sweetness/Off-Dry | hint of sweetness | 81 |
| Sweetness/Off-Dry | touch of residual sugar | 32 |
| Sweetness/Sweet | dessert wine | 506 |
| Sweetness/Sweet | lusciously sweet | 14 |
| Sweetness/Sweet | honeyed sweetness | 39 |

## Executed query checks

Passed 23 compiled-query executions on the bundled database plus synthetic checks for OR/AND behavior, exact Type membership, misleading wine names, ambiguous phrases, case-insensitive geography, quoted user input, ranking, and null scores.

Counts establish data presence, not professional validation of wine classification or phrase accuracy. Substring evidence may still misread context or negation.
