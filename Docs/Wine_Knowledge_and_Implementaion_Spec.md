# Wine Knowledge Implementation Specification

## Objective
Implement a wine-production knowledge system for a mobile application that answers both general and geography-specific questions. The same production keys should be reused across all scopes, while values become more specific as geography and grape context become more specific.

## Core Retrieval Principle
Use the most specific available knowledge first, then fall back progressively:

1. appellation + grape + process
2. region + grape + process
3. country + grape + process
4. grape + process
5. wine style + process
6. general process

Never invent a more-specific answer when only broader knowledge exists. Mark inherited answers as inherited.

## Knowledge Hierarchy

```text
General wine process
→ Wine-style process
→ Grape-specific process
→ Country-specific practice
→ Region-specific practice
→ Appellation-specific rule/practice
→ Producer-specific practice
```

## Stable Production Keys
Use these canonical process keys across all records:

```text
harvest
sorting
crushing_destemming
pressing
maceration
fermentation
malolactic_fermentation
extraction_cap_management
maturation
lees_management
blending
clarification
filtration
stabilisation
bottling
bottle_ageing
```

Optional extended keys:

```text
vineyard_management
irrigation
yield_management
must_preparation
fortification
sparkling_secondary_fermentation
sweet_wine_concentration
biological_ageing
oxidative_ageing
```

## Geography Model
Do not hard-code a fixed number of geography levels.

```text
geo_entity
- id
- name
- geo_type
- parent_geo_entity_id
- country_code
- aliases[]
```

Example hierarchy:

```text
France
└── Bordeaux
    └── Right Bank
        └── Saint-Émilion
```

Supported `geo_type` values can include:

```text
country
macro_region
region
subregion
appellation
commune
vineyard
```

## Core Entities

### Grape

```text
grape
- id
- canonical_name
- aliases[]
- colour
- description
```

### Wine Style

```text
wine_style
- id
- name
```

Suggested values:

```text
red_still
white_still
rose
orange_amber
sparkling
sweet
fortified
flor
```

### Production Process

```text
production_process
- id
- key
- canonical_name
- parent_process_id
- synonyms[]
- definition
```

### Regional Practice
This is the main table for specific production knowledge.

```text
regional_practice
- id
- geo_entity_id nullable
- grape_id nullable
- wine_style_id nullable
- process_id
- practice_status
- prevalence
- answer_text
- source_scope
- knowledge_scope
- confidence
- valid_from nullable
- valid_to nullable
- last_verified nullable
```

## Practice Status
Use controlled values:

```text
REQUIRED
PERMITTED
PROHIBITED
COMMON
FREQUENT
VARIABLE
OCCASIONAL
TRADITIONAL
EMERGING
RARE
PRODUCER_SPECIFIC
UNKNOWN
```

## Knowledge Scope

```text
DIRECT
INHERITED
INFERRED
```

`INHERITED` means the answer came from a parent geography or broader grape/process record.

## Provenance
Every substantive claim should be traceable.

```text
source
- id
- title
- publisher
- source_type
- url
- publication_date nullable
- effective_date nullable
- last_verified nullable
- credibility_score
```

Suggested `source_type` values:

```text
official_regulation
official_appellation_body
government
oiv
peer_reviewed
university
technical_institute
reference_book
producer_technical_sheet
trade_publication
general_web
```

## Source Priority
Prefer in this order:

```text
1. official regulation / appellation authority
2. OIV / government
3. peer-reviewed / university / technical institute
4. authoritative reference source
5. producer technical material
6. trade press
7. general web
```

## Confidence Model
Recommended internal confidence score:

```text
confidence =
0.35 * source_authority +
0.20 * geographic_specificity +
0.15 * recency +
0.15 * corroboration +
0.15 * claim_specificity
```

Store as 0.0–1.0.

## MVP Answer Model
The app can expose a simple key:value production answer while preserving structured context.

```json
{
  "subject": "Merlot",
  "geography": "Bordeaux",
  "answer_scope": "regional",
  "production": {
    "harvest": "...",
    "sorting": "...",
    "crushing_destemming": "...",
    "maceration": "...",
    "fermentation": "...",
    "malolactic_fermentation": "...",
    "maturation": "...",
    "blending": "..."
  },
  "sources": [],
  "confidence": 0.92
}
```

## Composite Lookup
Treat the logical key as a composite of context rather than one flat string.

```text
grape:merlot
region:bordeaux + grape:merlot
region:bordeaux + grape:merlot + process:fermentation
appellation:saint_emilion + grape:merlot + process:maturation
```

An implementation may materialize this as indexed columns rather than literal string keys.

## Retrieval Algorithm

### Input
Natural-language query.

### Step 1: Intent Detection
Classify into one of:

```text
DEFINE
PROCESS
REGION_PROCESS
GRAPE_REGION
COMPARE
WHY
EFFECT
REGULATION
METHOD
TERROIR
```

### Step 2: Entity Extraction
Extract where available:

```text
grape
country
region
appellation
wine_style
process
```

### Step 3: Retrieve Most Specific Match
Try in this order:

```text
appellation + grape + process
region + grape + process
country + grape + process
grape + process
wine_style + process
general process
```

### Step 4: Fill Missing Process Keys
For broad questions such as "How is Merlot made?", retrieve multiple process keys and return a production map.

### Step 5: Mark Scope
Return whether each field is direct or inherited.

### Step 6: Answer Synthesis
Generate concise natural-language output from structured values. Do not overwrite structured facts with unsupported model knowledge.

## Example Records

### General Merlot

```json
{
  "id": "RP-MERLOT-GENERAL",
  "geo_entity_id": null,
  "grape_id": "GRAPE-MERLOT",
  "wine_style_id": "red_still",
  "process_id": "PROCESS-GENERAL",
  "practice_status": "COMMON",
  "prevalence": "VARIABLE",
  "knowledge_scope": "DIRECT",
  "answer_text": "Merlot is commonly made as a red wine with fermentation on the skins, extraction management, malolactic fermentation in many styles, and maturation in tank, concrete, wood, or combinations depending on the intended wine style.",
  "confidence": 0.88
}
```

### Bordeaux Merlot

```json
{
  "id": "RP-BORDEAUX-MERLOT-GENERAL",
  "geo_entity_id": "GEO-BORDEAUX",
  "grape_id": "GRAPE-MERLOT",
  "wine_style_id": "red_still",
  "process_id": "PROCESS-GENERAL",
  "practice_status": "COMMON",
  "prevalence": "VARIABLE",
  "knowledge_scope": "DIRECT",
  "answer_text": "In Bordeaux, Merlot is widely used in red blends, especially on the Right Bank. Production commonly involves fermentation on skins, managed extraction, malolactic fermentation, blending, and maturation in tank or wood depending on estate style and appellation.",
  "confidence": 0.92
}
```

### Saint-Émilion Merlot

```json
{
  "id": "RP-SAINT-EMILION-MERLOT-GENERAL",
  "geo_entity_id": "GEO-SAINT-EMILION",
  "grape_id": "GRAPE-MERLOT",
  "wine_style_id": "red_still",
  "process_id": "PROCESS-GENERAL",
  "practice_status": "COMMON",
  "prevalence": "VARIABLE",
  "knowledge_scope": "DIRECT",
  "answer_text": "In Saint-Émilion, Merlot is a major component of many red wines. Vinification varies by estate, but fermentation on skins, extraction management, malolactic fermentation, blending with other permitted varieties, and maturation in tank, barrel, or other vessels are common choices.",
  "confidence": 0.93
}
```

## Example Query Behavior

### Query: "How is Merlot made?"
Expected behavior:
- detect grape = Merlot
- no geography supplied
- retrieve grape-specific general production records
- return a multi-key production answer
- do not assume Bordeaux or another region

### Query: "How is Merlot made in Bordeaux?"
Expected behavior:
- detect grape = Merlot
- detect region = Bordeaux
- retrieve Bordeaux + Merlot records first
- use general Merlot records only to fill gaps
- mark fallback fields as inherited

### Query: "How is Merlot fermented in Bordeaux?"
Expected behavior:
- detect grape = Merlot
- detect region = Bordeaux
- detect process = fermentation
- return only fermentation-related production knowledge unless supporting context is necessary

### Query: "How is Merlot made in Saint-Émilion?"
Expected behavior:
- retrieve Saint-Émilion + Merlot first
- then Bordeaux + Merlot
- then France + Merlot
- then general Merlot
- preserve source scope for every returned field

## Suggested API Shape

### Request

```json
{
  "query": "How is Merlot made in Bordeaux?"
}
```

### Parsed Request

```json
{
  "intent": "GRAPE_REGION",
  "grape": "merlot",
  "geo_entity": "bordeaux",
  "process": null
}
```

### Response

```json
{
  "subject": "Merlot",
  "geography": "Bordeaux",
  "answer_scope": "regional",
  "production": {
    "harvest": {
      "value": "...",
      "scope": "regional",
      "knowledge_scope": "DIRECT"
    },
    "fermentation": {
      "value": "...",
      "scope": "regional",
      "knowledge_scope": "DIRECT"
    },
    "maturation": {
      "value": "...",
      "scope": "regional",
      "knowledge_scope": "DIRECT"
    }
  },
  "sources": [],
  "confidence": 0.92
}
```

## Implementation Rules

1. Do not duplicate full articles per geography when the same process concept can be inherited.
2. Do not mix legal requirements with common practice.
3. Do not present `COMMON` as `REQUIRED`.
4. Prefer specific geography over broad geography.
5. Preserve the source and confidence of each retrieved claim.
6. Allow null geography for universal or grape-level knowledge.
7. Allow null grape for geography-wide process knowledge.
8. Support synonyms for grapes, regions, appellations, and production techniques.
9. Keep canonical IDs stable even if display names change.
10. Do not let LLM-generated prose become the source of truth unless it is reviewed and stored as a sourced record.

## Acceptance Criteria

The implementation is complete when all of the following work:

- "How is Merlot made?" returns grape-level production values.
- "How is Merlot made in Bordeaux?" returns Bordeaux-specific values first.
- "How is Merlot made in Saint-Émilion?" returns Saint-Émilion-specific values first and falls back cleanly.
- "How is Merlot fermented in Bordeaux?" returns process-specific information, not an unrelated full article.
- Missing regional fields inherit from the nearest valid parent scope.
- Every returned claim includes source/provenance metadata internally.
- Legal/regulatory records are distinguishable from customary practices.
- The response exposes whether knowledge is DIRECT or INHERITED.
- New regions can be added without changing the production key taxonomy.
- New grapes can be added without duplicating the production-process schema.

## Recommended MVP Tables

```text
geo_entity
grape
wine_style
production_process
regional_practice
source
regional_practice_source
```

Optional later tables:

```text
regulation
producer
producer_practice
vessel
production_technique
claim
claim_source
```

## Development Priority

1. Implement schema and migrations.
2. Seed canonical production-process keys.
3. Seed geography hierarchy.
4. Seed grape dictionary.
5. Implement specific-to-general retrieval.
6. Add provenance/confidence.
7. Add natural-language entity extraction.
8. Add answer synthesis.
9. Add tests for inheritance and scope.
10. Populate real wine-production data.

