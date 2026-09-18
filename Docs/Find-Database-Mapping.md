# Find → Kaggle database mapping

**Status:** Implemented and checked against the bundled `wine_reviews.db`, 17 September 2026; Tannin/Acidity/Body switched from `review_summary` phrase matching to precomputed columns, 18 September 2026. This replaces the attached draft's stale schema, ten-country limit, and guaranteed-empty style assumptions. Presence checks are not professional certification of wine style or tasting characteristics.

## Mapping contract

| Find selection | Database field | Rule |
|---|---|---|
| Type | `variety` | Case-insensitive equality against the union of mapped varieties for all selected types. No name or review-based Type inference. |
| Country | `country` | Case-insensitive equality; omit if unselected. |
| Province | `province` | Case-insensitive equality; omit if unselected. |
| Tannin | `tannin` | Case-insensitive equality against Smooth / Moderate / Astringent. |
| Acidity | `acidity` | Case-insensitive equality against Soft / Crisp / Tart. |
| Body | `body` | Case-insensitive equality against Light-Bodied / Medium-Bodied / Full-Bodied. |
| Sweetness | `review_summary` | Any phrase for Bone-Dry / Off-Dry / Sweet. |

Use OR among selected alternatives within a field (an `IN (...)` match for Tannin/Acidity/Body, phrase alternatives for Sweetness), and AND across all selected fields. Unselected fields contribute no condition. All values use bound SQL parameters. Never query the whole database with no criteria. Return at most three rows ordered by points descending, winery ascending, then ID ascending, with null points below every numeric score.

Find supports multi-selection, so the draft's single-value example is generalized to sets. The query receives only the submitted user criteria and never depends on Gemma output. Gemma still receives the same field choices as allowed-value lists independently, and returns one selected value per constrained field.

## Tannin/Acidity/Body: precomputed columns, not review text

The `tannin`, `acidity`, and `body` columns were added to `wine_reviews` on 18 September 2026 by classifying each **unique wine** (grouped by `name` + `winery`, 118,780 of 119,030 rows) from its concatenated review text, using keyword rules (e.g. "bold"/"rich"/"powerful" → Full-Bodied; red/rosé wines search tannin texture, others are "Not applicable"). Every review row for the same wine carries that wine's classification. Unclassified or ambiguous wines are stored as `Unknown` (or `Not applicable` for Tannin on non-red/rosé wines) and are excluded whenever that field is selected, the same as before.

This keyword classification is broader and less conservative than the phrase list it replaced (see "Phrase review decisions" below, which documents the **old** approach for Sweetness, still in effect) — words like "rich" or "bold" alone now assign Full-Bodied, where the prior phrase-based system deliberately excluded them as ambiguous. Treat Tannin/Acidity/Body results as a coarser signal than before; false positives from context or negation in the source reviews are more likely, not less.

## One source for labels and matching rules

`FindPhraseEvidence` defines each label. `GuidedOptions` derives its visible choices directly from it; criteria validation and Gemma's allowed-value prompt/parser use the same definitions. Tannin/Acidity/Body are plain label lists (`TANNIN_LABELS`/`ACIDITY_LABELS`/`BODY_LABELS`) — there is no phrase evidence to keep for them, since Find matches their precomputed columns directly rather than review text. Only Sweetness (`SWEETNESS_EVIDENCE`) still maps labels to the review-text phrases used to query `review_summary`. Labels cannot be renamed separately from their mapping. Unknown labels fail validation instead of silently removing a requested constraint.

The shared profile schema remains string-based. Find's generated profiles now use the descriptive labels; existing saved profiles with Low/Medium/High or ranges remain readable. Chat's phrase maps and behavior are unchanged.

## Type mapping and actual coverage

The supplied Red/White shortlist is included and extended with the existing application's grape groups and explicitly color-labelled blends, using exact spellings from the bundled database (including Carmenère, Mourvèdre, Sémillon, and Shiraz). No unknown grape is assigned a style by guessing from its name.

The assertion that Sparkling and Fortified lack data was incorrect. The literal value `Sparkling` is absent, but these exact values exist:

- Sparkling: Champagne Blend, Portuguese Sparkling, Prosecco, Sparkling Blend.
- Rosé: Portuguese Rosé, Rosé.
- Fortified: Madeira Blend, Port, Sherry, White Port.

Sweet is a Sweetness choice, not a Type. Riesling and Moscato do not imply Sweet; sweetness selections require review evidence. Chardonnay does not imply Sparkling. A title containing Champagne or Port does not override its stored variety.

`WineTypeVarietyMap.TYPE_TO_VARIETIES` contains the complete implemented whitelist. Its 64 variety values cover 104,269 of 119,030 rows (87.6%). **This is not an exhaustive classification of all 701 variety values.** The remaining 637 values are listed with counts in [Find-Unclassified-Varieties.csv](Find-Unclassified-Varieties.csv) for further dataset review. Those wines remain available when Type is unselected. Even a mapped grape can be made in several styles: exact variety matching cannot certify a bottle's color/style without a dedicated field.

All five visible Type choices have backing rows. A deliberately configured empty type mapping short-circuits as a known-empty query; an unknown label is rejected. For multiple selected types, an empty group contributes nothing to the union and does not suppress supported alternatives. No fallback runs.

## Phrase review decisions

The candidate lists were checked individually against the bundled reviews. The 55 retained phrases all occur in the data. The following conservative edits are deliberate:

| Candidate | Decision | Reason |
|---|---|---|
| firm tannins | Moderate | Follow the supplied tier assignment, replacing the previous High mapping. |
| crisp acidity | Crisp | Follow the supplied middle tier, replacing the previous High mapping. |
| rich, mild, tannic, tart, dry, sweet | Exclude bare words | They can describe flavor, other attributes, or unclear intensity. For example, rich occurs in 21,015 reviews and sweet in 14,645; frequency does not establish body or sweetness. |
| airy, weighty, opulent, heavy | Exclude bare adjectives | Too ambiguous to assign body without additional structural context. |
| thin-bodied | Exclude for now | No occurrence in the bundled reviews. |
| Remaining multi-word candidate phrases | Retain | Preserve explicit evidence and the proposed OR semantics. Counts are recorded in the audit. |

Bone-Dry uses bone-dry, bone dry, and crisp and dry. Off-Dry uses off-dry, off dry, semi-sweet, hint of sweetness, and touch of residual sugar. Sweet uses dessert wine, lusciously sweet, and honeyed sweetness. No generic “sweet fruit” match is accepted.

These are still substring matches. Context, negation, and descriptions containing multiple structural signals can produce false positives. Missing evidence produces false negatives, particularly for sweetness. Do not present selected preferences as facts in Database profiles.

Keep the existing review-evidence disclaimer and add a short note that Type filtering can miss unclassified varieties.

## Geography and schema corrections

The actual review column is `review_summary`, not `description`. Country and Province pickers continue to use all 43 countries and 425 distinct provinces present in the database; the ten-country restriction in the draft is obsolete. Locations outside that former shortlist are not known-empty cases. `USA` remains a display alias for `US`, and Province remains the stored `province` field.

## Verification and reproducibility

See [Find-Mapping-Audit.md](Find-Mapping-Audit.md) for exact per-type and per-phrase row counts. After compiling the app, run `scripts/audit_find_mapping.py` with `JAVA_HOME` set to a JDK. It exports the **compiled Kotlin query builder's actual SQL**, executes it against the read-only database, and verifies synthetic regressions for multiple-choice OR groups, cross-field AND, ranking, null scores, quoting, misleading titles, and ambiguous phrase exclusions. It also refreshes the unclassified inventory.

Instrumented tests cover query and Gemma parsing behavior but must only run on an emulator/managed virtual device. Without one, compile the test APK and report those runtime checks as pending.
