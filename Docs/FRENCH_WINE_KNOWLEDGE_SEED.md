# French Wine Knowledge Seed Spec

This file contains two database-ready French wine production samples plus a starter list of five major French grape varieties.

## Purpose

Use this as seed knowledge for the wine-production knowledge base.

The application should resolve answers from general to specific:

General process
→ Grape
→ Country
→ Region
→ Appellation
→ Producer-specific practice

More specific knowledge overrides broader knowledge. Missing fields inherit from the nearest broader scope.

---

# Sample 1 — Merlot, France → Bordeaux

```json
{
  "id": "FR-MERLOT-001",
  "country": "France",
  "grape": "Merlot",
  "wine_style": "red_still",
  "scope": "country",
  "parent_scope": "general_merlot",

  "production": {
    "harvest": {
      "value": "Merlot is an early-ripening red variety. Harvest timing depends on site, vintage and intended wine style.",
      "practice_status": "VARIABLE"
    },

    "sorting": {
      "value": "Grapes may be sorted before fermentation, particularly for quality-focused production.",
      "practice_status": "COMMON"
    },

    "destemming_crushing": {
      "value": "Destemming is common for red Merlot production, although whole berries or some whole clusters may be retained depending on producer style.",
      "practice_status": "VARIABLE"
    },

    "maceration": {
      "value": "Merlot is normally fermented in contact with its skins to extract colour, tannin and flavour compounds.",
      "practice_status": "COMMON"
    },

    "fermentation": {
      "value": "Alcoholic fermentation converts grape sugars into alcohol. Temperature and extraction management vary according to the desired style.",
      "practice_status": "COMMON"
    },

    "malolactic": {
      "value": "Malolactic fermentation is commonly used in dry red Merlot production.",
      "practice_status": "COMMON"
    },

    "maturation": {
      "value": "Merlot may mature in stainless steel, concrete or wooden vessels, including oak barrels. Vessel choice and ageing duration depend on wine style.",
      "practice_status": "VARIABLE"
    },

    "blending": {
      "value": "Merlot may be bottled varietally or blended with other varieties.",
      "practice_status": "VARIABLE"
    }
  },

  "sources": [
    {
      "publisher": "FranceAgriMer",
      "type": "government_industry_data",
      "credibility": 98
    }
  ],

  "confidence": 0.93
}
```

## Bordeaux override

```json
{
  "id": "FR-BDX-MERLOT-001",
  "country": "France",
  "region": "Bordeaux",
  "grape": "Merlot",
  "scope": "region",
  "inherits_from": "FR-MERLOT-001",

  "regional_overrides": {
    "grape_role": {
      "value": "Merlot is the dominant red grape of Bordeaux and is especially important in Saint-Émilion, Pomerol and Fronsac.",
      "practice_status": "REGIONAL_CHARACTERISTIC"
    },

    "blending": {
      "value": "Merlot is very commonly blended with Cabernet Sauvignon, Cabernet Franc and other permitted Bordeaux varieties.",
      "practice_status": "COMMON"
    },

    "style": {
      "value": "Bordeaux Merlot ranges from supple, fruit-driven wines to concentrated wines designed for extended ageing.",
      "practice_status": "REGIONAL_CHARACTERISTIC"
    }
  },

  "sources": [
    {
      "publisher": "Conseil Interprofessionnel du Vin de Bordeaux",
      "source": "Bordeaux.com",
      "credibility": 97
    }
  ],

  "confidence": 0.97
}
```

Expected behavior:

- Query: `How is Merlot made?`
  - Use the general Merlot profile.
- Query: `How is Merlot made in France?`
  - Use `FR-MERLOT-001`.
- Query: `How is Merlot made in Bordeaux?`
  - Use `FR-BDX-MERLOT-001`, inheriting missing production fields from `FR-MERLOT-001`.

---

# Sample 2 — Chardonnay, France → Burgundy

```json
{
  "id": "FR-CHARDONNAY-001",
  "country": "France",
  "grape": "Chardonnay",
  "wine_style": "white_still",
  "scope": "country",

  "production": {
    "harvest": {
      "value": "Harvest timing is selected according to desired sugar, acidity and flavour maturity.",
      "practice_status": "VARIABLE"
    },

    "pressing": {
      "value": "For conventional white Chardonnay production, grapes are generally pressed before alcoholic fermentation.",
      "practice_status": "COMMON"
    },

    "clarification": {
      "value": "Juice may be settled or otherwise clarified before fermentation.",
      "practice_status": "COMMON"
    },

    "fermentation": {
      "value": "Chardonnay may be fermented in stainless steel, concrete or wooden vessels including oak barrels.",
      "practice_status": "VARIABLE"
    },

    "malolactic": {
      "value": "Malolactic fermentation may be complete, partial or prevented according to the desired style.",
      "practice_status": "VARIABLE"
    },

    "lees": {
      "value": "Lees ageing and lees stirring may be used to modify texture and flavour.",
      "practice_status": "VARIABLE"
    },

    "maturation": {
      "value": "Maturation may take place in tank, concrete or oak depending on regional and producer style.",
      "practice_status": "VARIABLE"
    }
  },

  "confidence": 0.93
}
```

## Burgundy override

```json
{
  "id": "FR-BURG-CHARDONNAY-001",
  "country": "France",
  "region": "Burgundy",
  "grape": "Chardonnay",
  "wine_style": "white_still",
  "scope": "region",
  "inherits_from": "FR-CHARDONNAY-001",

  "regional_overrides": {
    "pressing": {
      "value": "White Burgundy grapes are typically pressed before fermentation; whole or crushed bunches may be pressed, and skin extraction is generally not the objective.",
      "practice_status": "COMMON"
    },

    "clarification": {
      "value": "The pressed juice is commonly settled to remove heavier solids before fermentation. Settling may occur naturally or be assisted by cooling or enzymes.",
      "practice_status": "COMMON"
    },

    "fermentation": {
      "value": "Alcoholic fermentation may take place in tanks or oak barrels and may use selected or indigenous yeasts.",
      "practice_status": "COMMON"
    },

    "malolactic": {
      "value": "Malolactic fermentation is characteristic of traditional white Burgundy vinification and is commonly used to reduce acidity and contribute roundness.",
      "practice_status": "COMMON"
    }
  },

  "sources": [
    {
      "publisher": "Bourgogne Wine Board",
      "source": "Vins de Bourgogne",
      "credibility": 97
    }
  ],

  "confidence": 0.97
}
```

Expected behavior:

- Query: `How is Chardonnay made?`
  - Use the general Chardonnay profile.
- Query: `How is Chardonnay made in France?`
  - Use `FR-CHARDONNAY-001`.
- Query: `How is Chardonnay made in Burgundy?`
  - Use `FR-BURG-CHARDONNAY-001`, inheriting missing fields from `FR-CHARDONNAY-001`.

---

# France Starter Varieties

Use these as the first five French grape varieties in the seed dataset:

1. Ugni Blanc
2. Merlot
3. Grenache Noir
4. Syrah
5. Chardonnay

Recommended starter geography:

```text
FRANCE
│
├── UGNI_BLANC
│   ├── France
│   └── Charentes / Cognac
│
├── MERLOT
│   ├── France
│   ├── Bordeaux
│   ├── Saint-Émilion
│   └── Pomerol
│
├── GRENACHE_NOIR
│   ├── France
│   ├── Rhône
│   └── Languedoc-Roussillon
│
├── SYRAH
│   ├── France
│   ├── Northern Rhône
│   └── Southern Rhône
│
└── CHARDONNAY
    ├── France
    ├── Burgundy
    │   ├── Chablis
    │   ├── Côte de Beaune
    │   └── Mâconnais
    └── Champagne
```

---

# Retrieval Priority

Use the most specific available knowledge first:

```text
appellation + grape + process
↓
region + grape + process
↓
country + grape + process
↓
grape + process
↓
general production process
```

Do not duplicate full production records at every geographic level. Store only overrides and inherit missing values from the closest parent scope.

---

# Suggested Production Keys

Use a stable set of production keys:

```text
harvest
sorting
destemming_crushing
pressing
maceration
fermentation
malolactic
lees
maturation
blending
clarification
filtration
stabilisation
bottling
```

Each key should support:

```json
{
  "value": "Human-readable production explanation",
  "practice_status": "COMMON | VARIABLE | REQUIRED | PERMITTED | PROHIBITED | TRADITIONAL | PRODUCER_SPECIFIC",
  "source_ids": [],
  "confidence": 0.0
}
```

---

# Source Notes

Primary sources used for this seed structure:

- FranceAgriMer — French vineyard and variety planting data.
- Conseil Interprofessionnel du Vin de Bordeaux / Bordeaux.com — Bordeaux grape and regional context.
- Bourgogne Wine Board / Vins de Bourgogne — Burgundy white-wine production practices.

The production text should be treated as curated explanatory knowledge, not as universal legal rules unless explicitly tagged `REQUIRED`.

---

# Implementation Acceptance Criteria

Codex / Claude Code should:

1. Add or update the wine knowledge schema to support:
   - grape
   - country
   - region
   - appellation
   - production keys
   - inheritance
   - practice status
   - source provenance
   - confidence

2. Seed the two examples:
   - Merlot → France → Bordeaux
   - Chardonnay → France → Burgundy

3. Add the five French starter varieties:
   - Ugni Blanc
   - Merlot
   - Grenache Noir
   - Syrah
   - Chardonnay

4. Implement general-to-specific fallback.

5. Ensure a specific regional record overrides only the fields it defines.

6. Preserve broader inherited values for all missing fields.

7. Return structured production answers suitable for conversion into natural-language responses.
