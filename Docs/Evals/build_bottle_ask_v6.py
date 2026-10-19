"""Builds Bottle detail ASK v6: 100 grape-knowledge questions, 35% of them about the Grape_Profile_Kaggle_Extracted table.

Composition (100):
  45 internal profiles   30 answerable (supported) + 15 false claims
  35 extracted table     20 answerable + 5 false claims (answered from the enthusiasts' rows)
                         5 bottle with no country (Gemma must ask which country or region)
                         5 country with no rows for the grape (Kotlin refuses: no notes found)
  20 invented names      Kotlin should refuse

Extracted-table expectations follow the app: the DAO returns rows for the bottle's country and grape ordered by province
(bottle province first), at most 3, and the notes tell Gemma to credit "wine enthusiasts" and to say "limited evidence"
for starred values. Only grapes that are not in the internal profiles are used for the extracted cases.

Run:  python3 build_bottle_ask_v6.py
Writes app/src/debug/assets/eval/bottle_ask_v6.json and Docs/Evals/bottle-ask-v6/questions.csv.
"""
import csv
import hashlib
import json
import random
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = 20261020
rng = random.Random(SEED)
ASSETS = ROOT / "app/src/main/assets"
KNOWLEDGE = ASSETS / "knowledge/grape_profile_internal.jsonl"
EXTRACTED = ASSETS / "grape_profile_kaggle_extracted.json"
VARIETIES = ROOT / "app/src/main/java/com/sheldondesousa/uncork/data/reviews/GrapeVarieties.kt"
WINERIES = ASSETS / "knowledge/wineries_directory.csv"
PROMPT = ASSETS / "prompts/wine_discussion_instruction.txt"
OUT = ROOT / "app/src/debug/assets/eval/bottle_ask_v6.json"
CSV_OUT = ROOT / "Docs/Evals/bottle-ask-v6/questions.csv"
PRIOR = [json.loads((ROOT / f"app/src/debug/assets/eval/bottle_ask_v{n}.json").read_text()) for n in (4, 5)]


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(c)).strip()


# ------------------------------------------------------------------ data
rows = [json.loads(line) for line in KNOWLEDGE.read_text().splitlines() if line.strip()]
ALIAS_ROWS = {"Garnacha", "Pinot Grigio"}
internal = [r for r in rows if r["grape"] not in ALIAS_ROWS]
internal_names = set()
for r in rows:
    for n in [r["grape"]] + re.split(r"[;,/]", r["also_known_as"]):
        if n.strip():
            internal_names.add(fold(n))
            internal_names.update(fold(x) for x in n.split("/") if x.strip())
internal_names |= {"syrah", "shiraz", "garnacha", "grenache", "pinot grigio", "pinot gris"}

varieties = []  # (display name, [database names])
for name, dbs in re.findall(r'GrapeVariety\("([^"]+)", listOf\(([^)]*)\)\)', VARIETIES.read_text()):
    varieties.append((name, re.findall(r'"([^"]+)"', dbs)))


def covered_by_internal(name, dbs):
    names = {fold(name)} | {fold(x) for x in name.split("/")} | {fold(d) for d in dbs}
    return bool(names & internal_names)


extracted_rows = json.loads(EXTRACTED.read_text())
by_db = defaultdict(list)
for r in extracted_rows:
    by_db[fold(r["variety"])].append(r)

grapes = []  # extracted-only grapes, with their rows
for name, dbs in varieties:
    if covered_by_internal(name, dbs):
        continue
    rows_for = [r for d in dbs for r in by_db.get(fold(d), [])]
    if rows_for:
        grapes.append({"name": name, "dbs": dbs, "rows": rows_for})

prior_questions = {c["question"].lower() for d in PRIOR for c in d["cases"]}
prior_pairs = {(c["question_type"].replace("correct_", ""), tuple(c["source_grapes"])) for d in PRIOR for c in d["cases"]}


def has_value(r):
    return any(r[k] for k in ("body", "tannin", "acidity"))


def surfaced(grape, country, province=""):
    """What the app hands Gemma: DAO rows (country, spellings, any of body/tannin/acidity) by province, preferred first, 3 max."""
    dbs = {fold(d) for d in grape["dbs"]}
    hit = sorted((r for r in grape["rows"] if fold(r["country"]) == fold(country) and has_value(r)), key=lambda r: r["province"])[:12]
    hit.sort(key=lambda r: r["province"].lower() != province.lower())
    return hit[:3]


def words(value):
    return f"{value[:-1]} (limited evidence)" if value.endswith("*") else value


def plain(value):
    return value.rstrip("*").strip().lower()


NOTE = ("Assess semantic meaning using logged Kotlin inputs. Faithful explanations allowed; no fixed disagreement wording. "
        "Invented names lose factual credit. Grade only what Kotlin supplied; a field Kotlin did not supply is not required. "
        "Description questions: Gemma is limited to 1,000 characters or 6 sentences, so ageing and origin may be left out. If a "
        "question asks about one field and Kotlin supplied it, leaving it out is a Gemma error. Extracted-table answers must "
        "credit wine enthusiasts or reviewers, must not present the data as verified grape fact, and must say 'limited evidence' "
        "where the value was starred. Whole answer: 2 = covers what was asked with nothing wrong, missed or invented; 1 = partly "
        "right; 0 = most or all wrong, invented or missed. Attribute Kotlin-caused failures separately from Gemma.")
CREDIT = "Credits wine enthusiasts or reviewers; does not state it as verified grape fact"

cases = []


def add(category, qtype, source, bottle, question, expected, route, grapes_, required, optional=(), grading="human_review", extra=None):
    case = {"id": "", "category": category, "question_type": qtype, "knowledge_source": source,
            "bottle": {"name": f"Evaluation bottle — {bottle['variety']}", "variety": bottle["variety"],
                       "country": bottle.get("country", "Unknown"), "province": bottle.get("province", "Unknown")},
            "question": question, "expected_answer": expected, "expected_route": route, "source_grapes": grapes_,
            "grading": grading, "required_facts": list(required), "optional_facts": list(optional), "grading_note": NOTE,
            "family_id": f"{qtype}-{bottle['variety']}-{bottle.get('country', 'Unknown')}"}
    case.update(extra or {})
    cases.append(case)


def rotate(templates, count):
    order = []
    while len(order) < count:
        batch = templates[:]
        rng.shuffle(batch)
        order += batch
    return order[:count]


def fresh(question):
    return question.lower() not in prior_questions and question.lower() not in {c["question"].lower() for c in cases}


# ------------------------------------------------------------------ internal profiles: 30 answerable + 15 false claims
BODY = {"Very light-bodied": 0, "Light-bodied": 1, "Medium-bodied": 2, "Full-bodied": 3, "Very full-bodied": 4}
ACIDITY = {"Low": 1, "Moderate": 2, "Medium": 2, "Medium to high": 3, "High": 4, "Very high": 5}


def tannin_level(v):
    for key, level in (("None", 0), ("Low", 1), ("Moderate", 2), ("Very firm", 4), ("Firm", 3)):
        if v.startswith(key):
            return level
    return None


LEVEL = {"body": lambda p: BODY.get(p["body"]), "acidity": lambda p: ACIDITY.get(p["acidity"]),
         "tannin": lambda p: tannin_level(p["tannin"]) if p["tannin"] else None}


def origin(p):
    return ", ".join(x for x in (p["origin_region"], p["origin_country"]) if x)


def has(p, field):
    if field == "origin":
        return bool(origin(p))
    if field in LEVEL:
        return LEVEL[field](p) is not None
    return bool(p.get({"ageing": "ageing_potential"}.get(field, field)))


usage = Counter()
used = set()


def pick(field, kind, n=1):
    pool = [p for p in internal if has(p, field) and (kind, (p["grape"],)) not in prior_pairs and (kind, p["grape"]) not in used]
    rng.shuffle(pool)
    pool.sort(key=lambda p: usage[p["grape"]])
    chosen = pool[:n]
    for p in chosen:
        usage[p["grape"]] += 1
        used.add((kind, p["grape"]))
    return chosen


def aromas(p):
    return [a.strip() for a in p["aromas"].split(", ") if a.strip()]


T = {
    "body": ["What body does {g} usually have?", "Would you call {g} light, medium or full?", "How heavy does {g} feel in the mouth?", "What is the body of {g}?"],
    "acidity": ["How lively is the acidity in {g}?", "Would {g} taste sharp or soft?", "What is the acidity of {g}?", "Does {g} have bright acidity?"],
    "tannin": ["How grippy is {g}?", "What is the tannin like in {g}?", "Is {g} tannic?", "What tannin level do you have for {g}?"],
    "aromas": ["What can I smell in {g}?", "Which scents show up in {g}?", "What are the main aromas of {g}?", "What is on the nose of {g}?"],
    "ageing_potential": ["Will {g} improve with age?", "How many years can I keep {g}?", "What do you say about cellaring {g}?", "How well does {g} age?"],
    "origin": ["Where was {g} first grown?", "What country is {g} from?", "Where does {g} come from originally?", "What is the birthplace of {g}?"],
    "summary": ["What is {g} known for?", "Describe the character of {g} briefly.", "Give me the one-paragraph story of {g}.", "What should I know about {g}?"],
    "full_description": ["Tell me about the grape {g}.", "I'd like a full picture of {g}.", "Walk me through {g}.", "Describe {g} to a beginner."],
    "comparison": ["Which is higher in {f}, {a} or {b}?", "Contrast the {f} of {a} and {b}.", "Does {a} or {b} have more {f}?", "Compare {a} with {b} on {f}."]}

single = [("body", 4, lambda p: ([f"body: {p['body']}"], f"body: {p['body']}")),
          ("acidity", 4, lambda p: ([f"acidity: {p['acidity']}"], f"acidity: {p['acidity']}")),
          ("tannin", 4, lambda p: ([f"tannin: {p['tannin']}"], f"tannin: {p['tannin']}")),
          ("aromas", 3, lambda p: ([f"Aroma: {a}" for a in aromas(p)], "; ".join(f"Aroma: {a}" for a in aromas(p)))),
          ("ageing_potential", 3, lambda p: ([f"ageing_potential: {p['ageing_potential']}"], f"ageing_potential: {p['ageing_potential']}")),
          ("origin", 2, lambda p: ([f"origin: {origin(p)}"], f"origin: {origin(p)}")),
          ("summary", 2, lambda p: ([f"summary: {p['summary']}"], f"summary: {p['summary']}"))]
for kind, count, build in single:
    field = {"ageing_potential": "ageing"}.get(kind, kind)
    for p, template in zip(pick(field, kind, count), rotate(T[kind], count)):
        required, expected = build(p)
        add("supported", kind, "internal", {"variety": p["grape"]}, template.format(g=p["grape"]), expected, "WINE_DISCUSSION", [p["grape"]], required)

for p, template in zip(pick("summary", "full_description", 4), rotate(T["full_description"], 4)):
    required = [f"Summary meaning: {p['summary']}"] + [f"{l}: {p[f]}" for l, f in (("Body", "body"), ("Acidity", "acidity"), ("Tannin level/texture", "tannin")) if has(p, f)]
    required += [f"Aroma: {a}" for a in aromas(p)]
    optional = ([f"Ageing: {p['ageing_potential']}"] if p["ageing_potential"] else []) + ([f"Origin: {origin(p)}"] if origin(p) else [])
    add("supported", "full_description", "internal", {"variety": p["grape"]}, template.format(g=p["grape"]), " ".join(required + optional),
        "WINE_DISCUSSION", [p["grape"]], required, optional)

made = 0
for field in ("acidity", "acidity", "tannin", "body"):
    while True:
        a, b = pick(field, "comparison", 2)
        la, lb = LEVEL[field](a), LEVEL[field](b)
        if la != lb:
            break
        for p in (a, b):
            usage[p["grape"]] -= 1
            used.discard(("comparison", p["grape"]))
    hi, lo = (a, b) if la > lb else (b, a)
    required = [f"{hi['grape']}: {hi[field]} {field}", f"{lo['grape']}: {lo[field]} {field}", f"{hi['grape']} has higher recorded {field}"]
    add("supported", "comparison", "internal", {"variety": a["grape"]}, rng.choice(T["comparison"]).format(a=a["grape"], b=b["grape"], f=field),
        "; ".join(required), "WINE_DISCUSSION", [a["grape"], b["grape"]], required)

CLAIM = {"body": {0: "very light-bodied", 1: "light-bodied", 2: "medium-bodied", 3: "full-bodied", 4: "very full-bodied"},
         "acidity": {1: "low acidity", 2: "moderate acidity", 4: "high acidity", 5: "very high acidity"},
         "tannin": {0: "no tannins", 1: "low tannins", 2: "moderate tannins", 3: "firm tannins", 4: "very firm tannins"}}
FC = {"body": ["Someone told me {g} is {c}. Correct?", "{g} is {c}, isn't it?", "Can you confirm {g} is {c}?"],
      "acidity": ["Someone told me {g} has {c}. Correct?", "{g} has {c}, doesn't it?", "Can you confirm {g} has {c}?"],
      "tannin": ["Someone told me {g} has {c}. Correct?", "{g} has {c}, doesn't it?", "Can you confirm {g} has {c}?"]}
for field in ("body", "acidity", "tannin"):
    kind = f"correct_{field}"
    for p, template in zip(pick(field, kind, 5), rotate(FC[field], 5)):
        truth = LEVEL[field](p)
        claim = CLAIM[field][rng.choice([lv for lv in CLAIM[field] if abs(lv - truth) >= 2])]
        add("false_claim", kind, "internal", {"variety": p["grape"]}, template.format(g=p["grape"], c=claim),
            f"Not quite. {p['grape']}: {field} {p[field]}.", "WINE_DISCUSSION", [p["grape"]],
            [f"Politely correct the false {field} claim", f"{field}: {p[field]}"])

# ------------------------------------------------------------------ extracted table: 20 answerable + 5 false claims
rng.shuffle(grapes)
eg_used = set()


def extracted_pick(predicate, count):
    """Choose (grape, country, province) combinations whose surfaced rows satisfy predicate; each grape once."""
    out = []
    pool = [(g, c) for g in grapes for c in sorted({r["country"] for r in g["rows"]})]
    rng.shuffle(pool)
    pool.sort(key=lambda gc: len(surfaced(gc[0], gc[1])))  # few rows first: everything is surfaced, expectations are exact
    for g, country in pool:
        if g["name"] in eg_used or len(out) >= count:
            continue
        sel = surfaced(g, country)
        if sel and predicate(sel) and 1 <= len({r["province"] for r in sel}) == len(sel):
            n_all = len([r for r in g["rows"] if fold(r["country"]) == fold(country) and has_value(r)])
            if n_all <= 3 or rng.random() < 0.3:
                eg_used.add(g["name"])
                out.append((g, country, sel[0]["province"]))
    assert len(out) == count, (len(out), count)
    return out


def row_text(r, fields):
    parts = []
    for f in fields:
        if f == "flavours":
            parts.append("flavours " + ", ".join(r["flavor_notes"]))
        else:
            parts.append(f"{f} {words(r[f])}")
    return f"{r['province']}: " + "; ".join(parts)


EX_T = {
    "body": ["How full-bodied is {g}?", "What body does {g} usually have?", "Is {g} light or full in body?", "How heavy is {g} in the mouth, according to reviewers?"],
    "tannin": ["How tannic is {g}?", "What are the tannins like in {g}?", "Does {g} have much tannin?", "How grippy is {g}, according to reviewers?"],
    "acidity": ["How acidic is {g}?", "Is {g} high or low in acidity?", "How much acidity does {g} have?", "How sharp is {g}, according to reviewers?"],
    "flavours": ["What flavours does {g} have?", "What does {g} taste like?", "Which flavour notes show up in {g}?", "What flavours do reviewers pick out in {g}?"],
    "overview": ["Tell me about {g}.", "What is {g} like?", "Give me a quick overview of {g}.", "What do wine enthusiasts say about {g}?"]}
for kind in ("body", "tannin", "acidity", "flavours", "overview"):
    fields = {"overview": ["body", "tannin", "acidity", "flavours"], "flavours": ["flavours"]}.get(kind, [kind])

    def ok(sel, fields=fields, kind=kind):
        if kind == "overview":
            return all(r["flavor_notes"] and any(r[k] for k in ("body", "tannin", "acidity")) for r in sel)
        if kind == "flavours":
            return all(r["flavor_notes"] for r in sel)
        return all(r[kind] for r in sel)

    for (g, country, province), template in zip(extracted_pick(ok, 4), rotate(EX_T[kind], 4)):
        sel = surfaced(g, country, province)
        shown = [f for f in fields if f == "flavours" or any(r[f] for r in sel)] if kind == "overview" else fields
        lines = [row_text(r, [f for f in shown if f == "flavours" or r[f]]) for r in sel]
        question = template.format(g=g["name"])
        if kind == "overview" and rng.random() < 0.5:
            question = f"What do wine enthusiasts say about {g['name']} from {country}?"
        required = [CREDIT] + lines
        add("supported", f"extracted_{kind}", "extracted", {"variety": g["dbs"][0] if g["dbs"][0].lower() in {fold(d) for d in g["dbs"]} else g["name"],
                                                            "country": country, "province": province},
            question, f"Wine enthusiasts say, in {country}: " + " | ".join(lines), "WINE_DISCUSSION", [], required,
            extra={"extracted_grape": g["name"], "extracted_rows": [{k: r[k] for k in ("country", "province", "body", "tannin", "acidity", "flavor_notes")} for r in sel]})

SCALE = {"body": {"light": 1, "medium": 2, "full": 3}, "tannin": {"low": 1, "medium": 2, "high": 3}, "acidity": {"low": 1, "medium": 2, "high": 3}}
for field, count in (("body", 2), ("tannin", 2), ("acidity", 1)):
    def ok(sel, field=field):
        return all(plain(r[field] or "") in SCALE[field] and plain(r[field]) != "medium" for r in sel) if all(r[field] for r in sel) else False

    for (g, country, province), template in zip(extracted_pick(ok, count), rotate(["I read that {g} is {c}. Is that right?", "{g} is {c}, correct?"], count)):
        sel = surfaced(g, country, province)
        truth = plain(sel[0][field])
        opposite = {"light": "full", "full": "light", "low": "high", "high": "low"}[truth]
        claim = {"body": f"{opposite}-bodied", "tannin": f"{opposite} in tannin", "acidity": f"{opposite} in acidity"}[field]
        lines = [row_text(r, [field]) for r in sel]
        add("false_claim", f"extracted_correct_{field}", "extracted", {"variety": g["dbs"][0], "country": country, "province": province},
            template.format(g=g["name"], c=claim), f"Not quite. Wine enthusiasts say, in {country}: " + " | ".join(lines), "WINE_DISCUSSION", [],
            [f"Politely correct the false {field} claim", CREDIT] + lines,
            extra={"extracted_grape": g["name"], "extracted_rows": [{k: r[k] for k in ("country", "province", "body", "tannin", "acidity", "flavor_notes")} for r in sel]})

# ------------------------------------------------------------------ extracted table, edge cases: ask for place (5), no rows (5)
NO_PLACE_T = ["Tell me about {g}.", "What is {g} like?", "How full-bodied is {g}?", "What does {g} taste like?", "Give me a quick overview of {g}."]
pool = [g for g in grapes if g["name"] not in eg_used and len({r["country"] for r in g["rows"] if has_value(r)}) >= 2]
rng.shuffle(pool)
for g, template in zip(pool[:5], NO_PLACE_T):
    eg_used.add(g["name"])
    add("needs_place", "extracted_no_country", "extracted", {"variety": g["dbs"][0]}, template.format(g=g["name"]),
        "Asks which country or region the user means; states nothing about the grape.", "WINE_DISCUSSION", [],
        ["Asks which country or region", "Does not describe the grape from memory"],
        extra={"extracted_grape": g["name"]})
countries = sorted({r["country"] for r in extracted_rows})
NO_ROWS_T = ["How full-bodied is {g}?", "Tell me about {g}.", "What does {g} taste like?", "How tannic is {g}?", "What is {g} like?"]
for g, template in zip(pool[5:10], NO_ROWS_T):
    eg_used.add(g["name"])
    have = {fold(r["country"]) for r in g["rows"]}
    options = [c for c in countries if fold(c) not in have]
    country = rng.choice(options)
    # Kotlin finds no rows for this grape in this country, so the lookup refuses before Gemma is asked (checked in the golden test).
    add("no_information", "extracted_no_rows", "none", {"variety": g["dbs"][0], "country": country}, template.format(g=g["name"]),
        "I'm sorry, I do not have that information.", "KOTLIN_REFUSAL", [], ["I'm sorry, I do not have that information."],
        grading="exact", extra={"extracted_grape": g["name"], "country_without_rows": country})

# ------------------------------------------------------------------ invented names: 20
grape_names = [fold(r["grape"]) for r in rows] + [fold(d) for _, dbs in varieties for d in dbs] + [fold(n) for n, _ in varieties]
winery_text = WINERIES.read_text(errors="ignore").lower()
prior_text = " ".join(prior_questions)


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


ONSET = ["b", "br", "c", "d", "dr", "f", "g", "gl", "k", "kl", "l", "m", "n", "p", "pl", "r", "s", "sk", "t", "tr", "v", "w", "z"]
VOWEL = ["a", "e", "i", "o", "u", "ae", "ou"]
CODA = ["", "n", "r", "l", "x", "th", "rd"]
names = []
while len(names) < 20:
    n = "".join(rng.choice(ONSET) + rng.choice(VOWEL) + rng.choice(CODA) for _ in range(rng.choice((2, 3))))
    if not 6 <= len(n) <= 10 or n in names or n in winery_text or n in prior_text:
        continue
    if any(distance(n, g) < 3 for g in grape_names if abs(len(g) - len(n)) < 4):
        continue
    names.append(n)
T_UNKNOWN = ["Tell me about {n}.", "Which aromas does {n} have?", "What body would {n} have?", "How acidic is {n}?", "Where does {n} come from?",
             "What do your notes say about {n}?", "Describe {n} in plain English.", "How long can {n} age?"]
bottles = rotate([r["grape"] for r in internal], 20)
for i, (name, template) in enumerate(zip(names, rotate(T_UNKNOWN, 20))):
    shown = name.capitalize() if i % 2 == 0 else name
    add("no_information", "unknown_subject", "none", {"variety": bottles[i]}, template.format(n=shown),
        "I'm sorry, I do not have that information.", "KOTLIN_REFUSAL", [], ["I'm sorry, I do not have that information."], grading="exact")

# ------------------------------------------------------------------ finish
for number, case in enumerate(cases, 1):
    case["id"] = f"ASK6-{number:03d}"
# Invented names that Kotlin's lookup did not refuse when this set was built (lowercase name, certain phrasings). They reach
# Gemma, so they test Gemma's own decline. Re-check after any Kotlin lookup change.
MISSES = CSV_OUT.parent / "kotlin_lookup_misses.txt"
known_misses = set(MISSES.read_text().split()) if MISSES.exists() else set()
for case in cases:
    if case["id"] in known_misses:
        assert case["category"] == "no_information", case["id"]
        case["expected_kotlin_miss"] = True
counts = Counter(c["category"] for c in cases)
sources = Counter(c["knowledge_source"] for c in cases)
extracted_share = sum(1 for c in cases if c["question_type"].startswith("extracted_"))
assert len(cases) == 100, len(cases)
assert len({c["question"].lower() for c in cases}) == 100, "duplicate questions"
assert not ({c["question"].lower() for c in cases} & prior_questions), "overlaps v4 or v5"
assert extracted_share >= 25, extracted_share

meta = {"name": "Bottle detail ASK v6 — grape knowledge incl. extracted profiles, 100 questions", "version": 6, "entry": "bottle_detail_ask",
        "synthetic_bottles": True, "case_count": 100, "canonical_profile_count": len(internal), "raw_profile_count": len(rows),
        "category_counts": dict(counts), "question_type_counts": dict(Counter(c["question_type"] for c in cases)),
        "knowledge_source_counts": dict(sources), "extracted_table_questions": extracted_share,
        "extracted_table_share": extracted_share / 100,
        "expected_kotlin_lookup_misses": len(known_misses),
        "seed": SEED, "planned_runs": 1,
        "knowledge_sha256": hashlib.sha256(KNOWLEDGE.read_bytes()).hexdigest(),
        "extracted_sha256": hashlib.sha256(EXTRACTED.read_bytes()).hexdigest(),
        "prompt_sha256": hashlib.sha256(PROMPT.read_bytes()).hexdigest(),
        "pass_rule": "At least 90% of answers fully correct (grade 2), overall and in each category of 10 or more questions.",
        "notes": "Held-out set: do not tune the prompt or Kotlin against it. 35 questions concern the extracted table: 20 answerable, "
                 "5 false claims, 5 with no country (Gemma must ask), 5 with a country that has no rows for the grape (Kotlin refuses before "
                 "Gemma is asked). Extracted answers must credit wine enthusiasts and say 'limited evidence' for starred values. Expected rows replicate "
                 "the app: country + grape spellings, any of body/tannin/acidity, province order, preferred province first, 3 at most. "
                 "Profiles and keys are evaluation metadata only, never passed to Gemma."}
profiles = json.loads((ROOT / "app/src/debug/assets/eval/bottle_ask_v4.json").read_text())["profiles"]
OUT.write_text(json.dumps({"meta": meta, "profiles": profiles, "cases": cases}, ensure_ascii=False, indent=2) + "\n")
CSV_OUT.parent.mkdir(parents=True, exist_ok=True)
with CSV_OUT.open("w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(["id", "category", "question_type", "knowledge_source", "bottle", "country", "province", "question", "expected_answer"])
    for c in cases:
        writer.writerow([c["id"], c["category"], c["question_type"], c["knowledge_source"], c["bottle"]["variety"], c["bottle"]["country"],
                         c["bottle"]["province"], c["question"], c["expected_answer"][:240]])
print("categories", dict(counts))
print("knowledge sources", dict(sources))
print("invented names Kotlin does not refuse:", len(known_misses))
print("extracted-table questions:", extracted_share, "of 100")
print("types", dict(Counter(c["question_type"] for c in cases)))
