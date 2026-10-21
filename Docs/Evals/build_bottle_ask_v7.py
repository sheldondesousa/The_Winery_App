"""Builds Bottle detail ASK v7: 400 questions, half on the internal profiles and half on the extracted table.

Composition (400):
  200 internal   100 answerable (supported)
                  50 false claims (body, acidity, tannin of a grape in the internal notes)
                  25 no data: a grape that is in the notes, asked about a detail the notes never hold (Gemma must say it has no information)
                  25 no data: an invented grape that exists in neither source (Kotlin refuses: no notes found)
  200 extracted  100 answerable (answered from the enthusiasts' rows)
                  50 false claims (body, tannin, acidity from the enthusiasts' rows)
                  50 no data: a country with no rows for the grape (Kotlin refuses: no notes found)

Held-out against v4, v5 and v6: no question text repeats, and a (question kind, grape) pair is not reused from those sets where the
pool allows it.

Run:  python3 build_bottle_ask_v7.py
Writes app/src/debug/assets/eval/bottle_ask_v7.json and Docs/Evals/bottle-ask-v7/questions.csv.
"""
import csv
import hashlib
import json
import random
import re
import sqlite3
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = 20261021
rng = random.Random(SEED)
ASSETS = ROOT / "app/src/main/assets"
KNOWLEDGE = ASSETS / "knowledge/grape_profile_internal.jsonl"
EXTRACTED = ASSETS / "grape_profile_kaggle_extracted.json"
VARIETIES = ROOT / "app/src/main/java/com/sheldondesousa/uncork/data/reviews/GrapeVarieties.kt"
WINERIES = ASSETS / "knowledge/wineries_directory.csv"
REVIEWS_DB = ASSETS / "wine_reviews.db"
PROMPT = ASSETS / "prompts/wine_discussion_instruction.txt"
OUT = ROOT / "app/src/debug/assets/eval/bottle_ask_v7.json"
CSV_OUT = ROOT / "Docs/Evals/bottle-ask-v7/questions.csv"
PRIOR = [json.loads((ROOT / f"app/src/debug/assets/eval/bottle_ask_v{n}.json").read_text()) for n in (4, 5, 6)]


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(c)).strip()


# ------------------------------------------------------------------ data
rows = [json.loads(line) for line in KNOWLEDGE.read_text().splitlines() if line.strip()]
ALIAS_ROWS = {"Garnacha", "Pinot Grigio"}
internal = [r for r in rows if r["grape"] not in ALIAS_ROWS]
internal_names = set()
for r in rows:
    aliases = [re.sub(r"\s*\(.*?\)", "", part) for part in r["also_known_as"].split(";")]
    for n in [r["grape"]] + [x for a in aliases for x in a.split(",")]:
        if n.strip():
            internal_names.add(fold(n))
            internal_names.update(fold(x) for x in n.split("/") if x.strip())
internal_names |= {"syrah", "shiraz", "garnacha", "grenache", "pinot grigio", "pinot gris"}

varieties = []
for name, dbs in re.findall(r'GrapeVariety\("([^"]+)", listOf\(([^)]*)\)\)', VARIETIES.read_text()):
    varieties.append((name, re.findall(r'"([^"]+)"', dbs)))


def covered_by_internal(name, dbs):
    names = {fold(name)} | {fold(x) for x in name.split("/")} | {fold(d) for d in dbs}
    return bool(names & internal_names)


extracted_rows = json.loads(EXTRACTED.read_text())
by_db = defaultdict(list)
for r in extracted_rows:
    by_db[fold(r["variety"])].append(r)

grapes = []
for name, dbs in varieties:
    if covered_by_internal(name, dbs):
        continue
    rows_for = [r for d in dbs for r in by_db.get(fold(d), [])]
    if rows_for:
        grapes.append({"name": name, "dbs": dbs, "rows": rows_for})

prior_questions = {c["question"].lower() for d in PRIOR for c in d["cases"]}
prior_pairs = {(c["question_type"].replace("correct_", ""), tuple(c["source_grapes"])) for d in PRIOR for c in d["cases"]}
prior_extracted = {(("answer_" if c["category"] == "supported" else "") + c["question_type"].replace("extracted_correct_", "").replace("extracted_", ""), c.get("extracted_grape"))
                   for d in PRIOR for c in d["cases"] if c.get("extracted_grape")}


def has_value(r):
    return any(r[k] for k in ("body", "tannin", "acidity"))


def surfaced(grape, country, province=""):
    """What the app hands Gemma: rows for the country and the grape's spellings, by province, preferred first, 3 at most."""
    hit = sorted((r for r in grape["rows"] if fold(r["country"]) == fold(country) and has_value(r)), key=lambda r: r["province"])[:12]
    hit.sort(key=lambda r: r["province"].lower() != province.lower())
    return hit[:3]


def words(value):
    return f"{value[:-1]} (limited evidence)" if value.endswith("*") else value


def plain(value):
    return value.rstrip("*").strip().lower()


NOTE = ("Assess semantic meaning using logged Kotlin inputs. Faithful explanations allowed; no fixed disagreement wording. "
        "Invented names lose factual credit. Grade only what Kotlin supplied; a field Kotlin did not supply is not required. "
        "Extracted-table answers must credit wine enthusiasts or reviewers, must not present the data as verified grape fact, and "
        "must say 'limited evidence' where the value was starred. No-data questions: the reply must say the app has no information "
        "and must not describe the grape or give a value from memory. Whole answer: 2 = covers what was asked with nothing wrong, "
        "missed or invented; 1 = partly right; 0 = most or all wrong, invented or missed. Attribute Kotlin-caused failures separately "
        "from Gemma.")
CREDIT = "Credits wine enthusiasts or reviewers; does not state it as verified grape fact"
DECLINE = "I'm sorry, I do not have that information."

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


# ------------------------------------------------------------------ internal false claims (50)
BODY = {"Very light-bodied": 0, "Light-bodied": 1, "Medium-bodied": 2, "Full-bodied": 3, "Very full-bodied": 4}
ACIDITY = {"Low": 1, "Moderate": 2, "Medium": 2, "Medium to high": 3, "High": 4, "Very high": 5}


def tannin_level(v):
    for key, level in (("None", 0), ("Low", 1), ("Moderate", 2), ("Very firm", 4), ("Firm", 3)):
        if v.startswith(key):
            return level
    return None


LEVEL = {"body": lambda p: BODY.get(p["body"]), "acidity": lambda p: ACIDITY.get(p["acidity"]),
         "tannin": lambda p: tannin_level(p["tannin"]) if p["tannin"] else None}
usage = Counter()
used = set()


def origin(p):
    return ", ".join(x for x in (p["origin_region"], p["origin_country"]) if x)


def has(p, field):
    if field == "origin":
        return bool(origin(p))
    if field in LEVEL:
        return LEVEL[field](p) is not None
    return bool(p.get({"ageing": "ageing_potential"}.get(field, field)))


def pick(field, kind, n):
    """Distinct grapes per kind, not used in v4-v6; if the pool runs short a grape from an earlier set may come back."""
    chosen = []
    for relaxed in (False, True):
        pool = [p for p in internal if has(p, field) and (relaxed or (kind, (p["grape"],)) not in prior_pairs)
                and (kind, p["grape"]) not in used and p not in chosen]
        rng.shuffle(pool)
        pool.sort(key=lambda p: usage[p["grape"]])
        chosen += pool[:n - len(chosen)]
        if len(chosen) == n:
            break
    assert len(chosen) == n, (field, len(chosen), n)
    for p in chosen:
        usage[p["grape"]] += 1
        used.add((kind, p["grape"]))
    return chosen


def aromas(p):
    return [a.strip() for a in p["aromas"].split(", ") if a.strip()]


# ------------------------------------------------------------------ internal answerable (100)
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
single = [("body", 11, lambda p: ([f"body: {p['body']}"], f"body: {p['body']}")),
          ("acidity", 11, lambda p: ([f"acidity: {p['acidity']}"], f"acidity: {p['acidity']}")),
          ("tannin", 11, lambda p: ([f"tannin: {p['tannin']}"], f"tannin: {p['tannin']}")),
          ("aromas", 11, lambda p: ([f"Aroma: {a}" for a in aromas(p)], "; ".join(f"Aroma: {a}" for a in aromas(p)))),
          ("ageing_potential", 11, lambda p: ([f"ageing_potential: {p['ageing_potential']}"], f"ageing_potential: {p['ageing_potential']}")),
          ("origin", 11, lambda p: ([f"origin: {origin(p)}"], f"origin: {origin(p)}")),
          ("summary", 11, lambda p: ([f"summary: {p['summary']}"], f"summary: {p['summary']}"))]
for kind, count, build in single:
    field = {"ageing_potential": "ageing"}.get(kind, kind)
    for p, template in zip(pick(field, kind, count), rotate(T[kind], count)):
        required, expected = build(p)
        add("supported", kind, "internal", {"variety": p["grape"]}, template.format(g=p["grape"]), expected, "WINE_DISCUSSION", [p["grape"]], required)

for p, template in zip(pick("summary", "full_description", 12), rotate(T["full_description"], 12)):
    required = [f"Summary meaning: {p['summary']}"] + [f"{l}: {p[f]}" for l, f in (("Body", "body"), ("Acidity", "acidity"), ("Tannin level/texture", "tannin")) if has(p, f)]
    required += [f"Aroma: {a}" for a in aromas(p)]
    optional = ([f"Ageing: {p['ageing_potential']}"] if p["ageing_potential"] else []) + ([f"Origin: {origin(p)}"] if origin(p) else [])
    add("supported", "full_description", "internal", {"variety": p["grape"]}, template.format(g=p["grape"]), " ".join(required + optional),
        "WINE_DISCUSSION", [p["grape"]], required, optional)

for field in ("acidity",) * 5 + ("tannin",) * 3 + ("body",) * 3:
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

# ------------------------------------------------------------------ internal false claims (50)
CLAIM = {"body": {0: "very light-bodied", 1: "light-bodied", 2: "medium-bodied", 3: "full-bodied", 4: "very full-bodied"},
         "acidity": {1: "low acidity", 2: "moderate acidity", 4: "high acidity", 5: "very high acidity"},
         "tannin": {0: "no tannins", 1: "low tannins", 2: "moderate tannins", 3: "firm tannins", 4: "very firm tannins"}}
FC = {"body": ["Someone told me {g} is {c}. Correct?", "{g} is {c}, isn't it?", "Can you confirm {g} is {c}?", "I believe {g} is {c}. Am I right?"],
      "acidity": ["Someone told me {g} has {c}. Correct?", "{g} has {c}, doesn't it?", "Can you confirm {g} has {c}?", "I believe {g} has {c}. Am I right?"],
      "tannin": ["Someone told me {g} has {c}. Correct?", "{g} has {c}, doesn't it?", "Can you confirm {g} has {c}?", "I believe {g} has {c}. Am I right?"]}
for field, count in (("body", 17), ("acidity", 17), ("tannin", 16)):
    kind = f"correct_{field}"
    for p, template in zip(pick(field, kind, count), rotate(FC[field], count)):
        truth = LEVEL[field](p)
        claim = CLAIM[field][rng.choice([lv for lv in CLAIM[field] if abs(lv - truth) >= 2])]
        add("false_claim", kind, "internal", {"variety": p["grape"]}, template.format(g=p["grape"], c=claim),
            f"Not quite. {p['grape']}: {field} {p[field]}.", "WINE_DISCUSSION", [p["grape"]],
            [f"Politely correct the false {field} claim", f"{field}: {p[field]}"])

# ------------------------------------------------------------------ internal no data, part 1: a detail the notes never hold (25)
# The notes hold colour, body, acidity, tannin, sweetness, alcohol range, aromas, flavours, pairings, ageing, origin, regions
# and a summary. None of these details is held, and none is a question Kotlin answers itself (price, availability, production).
LACKS = {
    "serving temperature": ["What temperature should {g} be served at?", "How cold should I serve {g}?"],
    "soil": ["Which soils suit {g} best?", "What kind of soil does {g} like?"],
    "calories": ["How many calories are in a glass of {g}?", "Is {g} high in calories?"],
    "pH": ["What is the pH of {g}?", "How low is the pH in {g}?"],
    "sugar": ["How many grams of sugar per litre does {g} usually have?", "What is the residual sugar in {g}?"],
    "best vintages": ["Which vintages of {g} were the best?", "What was the best year for {g}?"],
    "clones": ["Which clones of {g} are planted most?", "What are the main clones of {g}?"],
    "glassware": ["Which glass should I use for {g}?", "What shape of glass suits {g}?"],
    "decanting": ["How long should I decant {g}?", "Does {g} need decanting?"],
    "chemistry": ["What is the polyphenol content of {g}?", "What is the resveratrol content of {g}?"],
}
lack_fields = list(LACKS)
pool = internal[:]
rng.shuffle(pool)
lack_cases = []
for i, p in enumerate(pool[:25]):
    field = lack_fields[i % len(lack_fields)]
    lack_cases.append((p, field, rng.choice(LACKS[field])))
for p, field, template in lack_cases:
    add("no_information", "internal_no_data_field", "internal", {"variety": p["grape"]}, template.format(g=p["grape"]), DECLINE,
        "WINE_DISCUSSION", [p["grape"]], [f"States it has no information about {field} for {p['grape']}", "Does not give a value or advice from memory"],
        extra={"missing_detail": field})

# ------------------------------------------------------------------ internal no data, part 2: an invented grape that exists nowhere (25)
INVENTED = ["Valdoria", "Corvessa", "Marquelle Noir", "Tintarra Bianca", "Sorrelino", "Brindavo", "Ostrelle", "Calderon Rosso", "Fenwick Blanc",
            "Lunessa", "Pardovino", "Quillara", "Rovesca Nera", "Tavelino", "Umbrelle", "Vessanta", "Xirelle", "Zanterra", "Alvarosa",
            "Belmorra", "Cresanta", "Dolomera", "Elmarino", "Ferrasca", "Gavendra"]
db = sqlite3.connect(REVIEWS_DB)
known = {fold(v[0]) for v in db.execute("select distinct variety from wine_reviews") if v[0]}
known |= internal_names | {fold(n) for n, _ in varieties} | {fold(r["variety"]) for r in extracted_rows}
winery_text = fold(WINERIES.read_text())
for name in INVENTED:
    assert fold(name) not in known, f"{name} exists in a source"
    assert fold(name) not in winery_text, f"{name} appears in the wineries directory"
    assert not any(fold(name) in fold(n) or fold(n) in fold(name) for n in known if len(n) > 4), f"{name} overlaps a real grape name"
NO_GRAPE_T = ["How full-bodied is {g}?", "Tell me about {g}.", "What does {g} taste like?", "How tannic is {g}?", "What is {g} like?",
              "How acidic is {g}?", "What flavours does {g} have?", "Where does {g} come from?"]
# The open bottle is a real grape from the notes; the question names the invented grape, which the app cannot match to anything.
open_bottles = rng.sample(internal, 25)
for name, template, bottle in zip(INVENTED, rotate(NO_GRAPE_T, 25), open_bottles):
    add("no_information", "internal_no_data_grape", "none", {"variety": bottle["grape"]}, template.format(g=name), DECLINE, "KOTLIN_REFUSAL", [],
        [DECLINE], grading="exact", extra={"invented_grape": name})

# ------------------------------------------------------------------ extracted false claims (50)
rng.shuffle(grapes)
eg_usage = Counter()
eg_kind = set()
SCALE = {"body": {"light": 1, "medium": 2, "full": 3}, "tannin": {"low": 1, "medium": 2, "high": 3}, "acidity": {"low": 1, "medium": 2, "high": 3}}


def extracted_pick(predicate, count, kind):
    """Each grape once per kind and not used in v4-v6; if that runs short, a grape may repeat in a different country."""
    out = []
    pool = [(g, c) for g in grapes for c in sorted({r["country"] for r in g["rows"]})]
    rng.shuffle(pool)
    pool.sort(key=lambda gc: (eg_usage[gc[0]["name"]], len(surfaced(gc[0], gc[1]))))
    for relaxed in (False, True):
        for g, country in pool:
            if len(out) >= count:
                break
            if (kind, g["name"], country) in eg_kind:
                continue
            if not relaxed and ((kind, g["name"]) in {k[:2] for k in eg_kind} or (kind, g["name"]) in prior_extracted):
                continue
            sel = surfaced(g, country)
            if sel and predicate(sel) and len({r["province"] for r in sel}) == len(sel):
                n_all = len([r for r in g["rows"] if fold(r["country"]) == fold(country) and has_value(r)])
                if n_all <= 3 or rng.random() < 0.3:
                    eg_kind.add((kind, g["name"], country))
                    eg_usage[g["name"]] += 1
                    out.append((g, country, sel[0]["province"]))
    assert len(out) == count, (kind, len(out), count)
    return out


def row_text(r, fields):
    parts = []
    for f in fields:
        parts.append("flavours " + ", ".join(r["flavor_notes"]) if f == "flavours" else f"{f} {words(r[f])}")
    return f"{r['province']}: " + "; ".join(parts)


# ------------------------------------------------------------------ extracted answerable (100)
EX_T = {
    "body": ["How full-bodied is {g}?", "What body does {g} usually have?", "Is {g} light or full in body?", "How heavy does {g} feel in the mouth?"],
    "tannin": ["How tannic is {g}?", "What are the tannins like in {g}?", "Does {g} have much tannin?", "How grippy is {g}?"],
    "acidity": ["How acidic is {g}?", "Is {g} high or low in acidity?", "How much acidity does {g} have?", "How sharp is {g} on the palate?"],
    "flavours": ["What flavours does {g} have?", "What does {g} taste like?", "Which flavour notes show up in {g}?", "What flavour notes can I expect from {g}?"],
    "overview": ["Tell me about {g}.", "What is {g} like?", "Give me a quick overview of {g}.", "What is {g} usually like?"]}
for kind in ("body", "tannin", "acidity", "flavours", "overview"):
    fields = {"overview": ["body", "tannin", "acidity", "flavours"], "flavours": ["flavours"]}.get(kind, [kind])

    def ok(sel, fields=fields, kind=kind):
        if kind == "overview":
            return all(r["flavor_notes"] and any(r[k] for k in ("body", "tannin", "acidity")) for r in sel)
        if kind == "flavours":
            return all(r["flavor_notes"] for r in sel)
        return all(r[kind] for r in sel)

    for (g, country, province), template in zip(extracted_pick(ok, 20, "answer_" + kind), rotate(EX_T[kind], 20)):
        sel = surfaced(g, country, province)
        shown = [f for f in fields if f == "flavours" or any(r[f] for r in sel)] if kind == "overview" else fields
        lines = [row_text(r, [f for f in shown if f == "flavours" or r[f]]) for r in sel]
        question = EX_T[kind][0].format(g=g["name"]) if False else template.format(g=g["name"])
        if kind == "overview" and rng.random() < 0.5:
            question = f"Tell me about {g['name']} from {country}."
        add("supported", f"extracted_{kind}", "extracted", {"variety": g["dbs"][0], "country": country, "province": province},
            question, f"Wine enthusiasts say, in {country}: " + " | ".join(lines), "WINE_DISCUSSION", [], [CREDIT] + lines,
            extra={"extracted_grape": g["name"], "extracted_rows": [{k: r[k] for k in ("country", "province", "body", "tannin", "acidity", "flavor_notes")} for r in sel]})

# ------------------------------------------------------------------ extracted false claims (50)
for field, count in (("body", 17), ("tannin", 17), ("acidity", 16)):
    def ok(sel, field=field):
        return all(plain(r[field] or "") in SCALE[field] and plain(r[field]) != "medium" for r in sel) if all(r[field] for r in sel) else False

    templates = ["I read that {g} is {c}. Is that right?", "{g} is {c}, correct?", "Would you say {g} is {c}?", "A friend says {g} is {c}. True?"]
    for (g, country, province), template in zip(extracted_pick(ok, count, field), rotate(templates, count)):
        sel = surfaced(g, country, province)
        truth = plain(sel[0][field])
        opposite = {"light": "full", "full": "light", "low": "high", "high": "low"}[truth]
        claim = {"body": f"{opposite}-bodied", "tannin": f"{opposite} in tannin", "acidity": f"{opposite} in acidity"}[field]
        lines = [row_text(r, [field]) for r in sel]
        add("false_claim", f"extracted_correct_{field}", "extracted", {"variety": g["dbs"][0], "country": country, "province": province},
            template.format(g=g["name"], c=claim), f"Not quite. Wine enthusiasts say, in {country}: " + " | ".join(lines), "WINE_DISCUSSION", [],
            [f"Politely correct the false {field} claim", CREDIT] + lines,
            extra={"extracted_grape": g["name"], "extracted_rows": [{k: r[k] for k in ("country", "province", "body", "tannin", "acidity", "flavor_notes")} for r in sel]})

# ------------------------------------------------------------------ extracted no data (50): a country with no rows for the grape
countries = sorted({r["country"] for r in extracted_rows})
NO_ROWS_T = ["How full-bodied is {g}?", "Tell me about {g}.", "What does {g} taste like?", "How tannic is {g}?", "What is {g} like?",
             "How acidic is {g}?", "What flavours does {g} have?", "Is {g} light or full in body?"]
prior_no_rows = {c.get("extracted_grape") for d in PRIOR for c in d["cases"] if c["question_type"] == "extracted_no_rows"}
candidates = [g for g in grapes if len({r["country"] for r in g["rows"] if has_value(r)}) >= 1 and g["name"] not in prior_no_rows]
candidates.sort(key=lambda g: (eg_usage[g["name"]], rng.random()))
rows_grapes = candidates[:50]
assert len(rows_grapes) == 50, len(rows_grapes)
for g, template in zip(rows_grapes, rotate(NO_ROWS_T, 50)):
    eg_usage[g["name"]] += 1
    have = {fold(r["country"]) for r in g["rows"]}
    country = rng.choice([c for c in countries if fold(c) not in have])
    add("no_information", "extracted_no_rows", "none", {"variety": g["dbs"][0], "country": country}, template.format(g=g["name"]), DECLINE,
        "KOTLIN_REFUSAL", [], [DECLINE], grading="exact", extra={"extracted_grape": g["name"], "country_without_rows": country})

# ------------------------------------------------------------------ finish
for number, case in enumerate(cases, 1):
    case["id"] = f"ASK7-{number:03d}"
MISSES = CSV_OUT.parent / "kotlin_lookup_misses.txt"
known_misses = set(MISSES.read_text().split()) if MISSES.exists() else set()
for case in cases:
    if case["id"] in known_misses:
        assert case["grading"] == "exact", case["id"]
        case["expected_kotlin_miss"] = True
        case["expected_route"] = "WINE_DISCUSSION"
        case["grading"] = "human_review"
        case["required_facts"] = ["States it has no information about the grape", "Does not describe the grape from memory"]
# A question whose text already appears in v4, v5 or v6 gets a short lead-in so the held-out rule (no repeated question text) holds.
for case in cases:
    if case["question"].lower() in prior_questions:
        case["question"] = "Quick check: " + case["question"][0].lower() + case["question"][1:] if not case["question"].split()[0].isupper() and case["question"].split()[0][0].islower() else "Quick check: " + case["question"]
counts = Counter(c["category"] for c in cases)
sources = Counter(c["knowledge_source"] for c in cases)
assert len(cases) == 400, len(cases)
assert len({c["question"].lower() for c in cases}) == 400, "duplicate questions"
overlap = [(c["id"], c["question_type"], c["question"]) for c in cases if c["question"].lower() in prior_questions]
assert not overlap, overlap
halves = Counter("extracted" if c["knowledge_source"] == "extracted" or c["question_type"].startswith("extracted_") else "internal" for c in cases)
assert halves == {"internal": 200, "extracted": 200}, halves

meta = {"name": "Bottle detail ASK v7 — answerable, false-claim and no-data questions, internal and extracted profiles, 400 questions", "version": 7,
        "entry": "bottle_detail_ask", "synthetic_bottles": True, "case_count": 400, "canonical_profile_count": len(internal),
        "raw_profile_count": len(rows), "category_counts": dict(counts), "question_type_counts": dict(Counter(c["question_type"] for c in cases)),
        "knowledge_source_counts": dict(sources), "half_counts": dict(halves), "expected_kotlin_lookup_misses": len(known_misses),
        "seed": SEED, "planned_runs": 1,
        "knowledge_sha256": hashlib.sha256(KNOWLEDGE.read_bytes()).hexdigest(),
        "extracted_sha256": hashlib.sha256(EXTRACTED.read_bytes()).hexdigest(),
        "prompt_sha256": hashlib.sha256(PROMPT.read_bytes()).hexdigest(),
        "pass_rule": "At least 90% of answers fully correct (grade 2), overall and in each category of 10 or more questions.",
        "notes": "Held-out set. 200 on the internal profiles (100 answerable, 50 false claims, 25 questions about a detail the notes "
                 "never hold, 25 about an invented grape that exists in neither source) and 200 on the extracted table (100 answerable, 50 false "
                 "claims, 50 with a country that has no rows for the grape). Invented names were checked against the grape list, both knowledge sources, the "
                 "reviews database and the wineries directory. Profiles and keys are evaluation metadata only, never passed to Gemma."}
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
print("halves", dict(halves))
print("types", dict(Counter(c["question_type"] for c in cases)))
