"""Builds Bottle detail ASK v5: 300 held-out questions, equal thirds, from the bundled grape notes.

100 answerable (should be answered from Kotlin's notes), 100 false claims (should be corrected from the notes),
100 unsupported (invented names, should be refused). Questions are new relative to v4: no repeated question text and
no repeated (question type, grape) pair. Grapes are used evenly, phrasing is rotated, and about 15% of the answerable and
false-claim questions use a lowercase grape name.

Run from anywhere:  python3 build_bottle_ask_v5.py
Writes app/src/debug/assets/eval/bottle_ask_v5.json and Docs/Evals/bottle-ask-v5/questions.csv.
"""
import csv
import hashlib
import json
import random
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = 20261019
rng = random.Random(SEED)

KNOWLEDGE = ROOT / "app/src/main/assets/knowledge/grape_profile_internal.jsonl"
WINERIES = ROOT / "app/src/main/assets/knowledge/wineries_directory.csv"
V4 = ROOT / "app/src/debug/assets/eval/bottle_ask_v4.json"
PROMPT = ROOT / "app/src/main/assets/prompts/wine_discussion_instruction.txt"
OUT = ROOT / "app/src/debug/assets/eval/bottle_ask_v5.json"
CSV_OUT = ROOT / "Docs/Evals/bottle-ask-v5/questions.csv"

rows = [json.loads(line) for line in KNOWLEDGE.read_text().splitlines() if line.strip()]
ALIAS_ROWS = {"Garnacha": "Grenache Noir", "Pinot Grigio": "Pinot Gris"}
profiles = [r for r in rows if r["grape"] not in ALIAS_ROWS]
by_name = {p["grape"]: p for p in profiles}
v4 = json.loads(V4.read_text())
v4_questions = {c["question"].lower() for c in v4["cases"]}
v4_pairs = {(c["question_type"].replace("correct_", ""), tuple(c["source_grapes"])) for c in v4["cases"]}

NOTE = ("Assess semantic meaning using logged Kotlin inputs. Faithful explanations allowed; no fixed disagreement wording. "
        "Invented names lose factual credit. Grade only what Kotlin supplied; a field Kotlin did not supply is not required. "
        "Description questions: Gemma is limited to 1,000 characters or 6 sentences, so the summary, body, acidity, tannin and "
        "aromas are required and ageing and origin are optional. If a question asks about one field and Kotlin supplied it, "
        "leaving it out is a Gemma error. Whole answer: 2 = covers what was asked with nothing wrong, missed or invented; "
        "1 = partly right; 0 = most or all wrong, invented or missed. Attribute Kotlin-caused failures separately from Gemma.")

BODY = {"Very light-bodied": 0, "Light-bodied": 1, "Medium-bodied": 2, "Full-bodied": 3, "Very full-bodied": 4}
ACIDITY = {"Low": 1, "Moderate": 2, "Medium": 2, "Medium to high": 3, "High": 4, "Very high": 5}


def tannin_level(value):
    for key, level in (("None", 0), ("Low", 1), ("Moderate", 2), ("Very firm", 4), ("Firm", 3)):
        if value.startswith(key):
            return level
    return None


LEVEL = {"body": lambda p: BODY.get(p["body"]), "acidity": lambda p: ACIDITY.get(p["acidity"]),
         "tannin": lambda p: tannin_level(p["tannin"]) if p["tannin"] else None}
WORD = {"body": "body", "acidity": "acidity", "tannin": "tannin"}


def origin(p):
    parts = [x for x in (p["origin_region"], p["origin_country"]) if x]
    return ", ".join(parts)


def has(p, field):
    if field == "origin":
        return bool(origin(p))
    if field in LEVEL:
        return LEVEL[field](p) is not None
    return bool(p.get({"ageing": "ageing_potential"}.get(field, field)))


usage = Counter()
used_pairs = set()


def pick(field, kind, n=1, exclude=()):
    """Pick n grapes that have the field, were not used for this kind in v4 or here, and are least used so far."""
    pool = [p for p in profiles if has(p, field) and p["grape"] not in exclude
            and (kind, (p["grape"],)) not in v4_pairs and (kind, p["grape"]) not in used_pairs]
    rng.shuffle(pool)
    pool.sort(key=lambda p: usage[p["grape"]])
    chosen = pool[:n]
    for p in chosen:
        usage[p["grape"]] += 1
        used_pairs.add((kind, p["grape"]))
    return chosen


def rotate(templates, count):
    order = []
    while len(order) < count:
        batch = templates[:]
        rng.shuffle(batch)
        order += batch
    return order[:count]


def lower_name(question, name):
    return question.replace(name, name.lower())


cases = []


def add(category, qtype, bottle_grape, question, expected, route, grapes, required, optional, grading="human_review", family=None):
    cases.append({
        "id": "", "category": category, "question_type": qtype,
        "bottle": {"name": f"Evaluation bottle — {bottle_grape}", "variety": bottle_grape, "country": "Unknown", "province": "Unknown"},
        "question": question, "expected_answer": expected, "expected_route": route, "source_grapes": grapes,
        "grading": grading, "required_facts": required, "optional_facts": optional, "grading_note": NOTE,
        "family_id": family or f"{qtype}-{bottle_grape}"})


def aromas(p):
    return [a.strip() for a in p["aromas"].split(", ") if a.strip()]


# ---------------------------------------------------------------- answerable: 100
T_BODY = ["How full-bodied is {g}?", "What body does {g} have according to your notes?", "Is {g} light or full in body?",
          "Describe the body of {g}.", "What weight should I expect from {g}?"]
T_ACID = ["How much acidity does {g} have?", "Is {g} high or low in acidity?", "What acidity do your notes record for {g}?",
          "How sharp is {g} on the palate?", "Tell me the acidity of {g}."]
T_TANN = ["How tannic is {g}?", "What are the tannins like in {g}?", "Does {g} have tannins?",
          "What does your data say about tannin in {g}?", "Tell me about the tannin in {g}."]
T_AROMA = ["What does {g} smell like?", "List the aromas in {g}.", "What aromas can I expect from {g}?",
           "What aroma notes do you have for {g}?", "Describe the nose of {g}."]
T_AGE = ["How long can {g} age?", "What is the ageing potential of {g}?", "Is {g} best young or aged?",
         "How does {g} age?", "Should I keep a bottle of {g} for years?"]
T_ORIGIN = ["Where is {g} from?", "What is the home region of {g}?", "Which country does {g} come from?",
            "Where does {g} originate according to your notes?", "Tell me the origin of {g}."]
T_SUMMARY = ["Summarise {g} in a sentence or two.", "What is {g} like in a nutshell?", "Give me your short summary of {g}.",
             "What is the personality of {g}?", "How does your summary characterise {g}?"]
T_FULL = ["Tell me everything important about {g}.", "Give me a quick profile of {g}.", "What is {g} all about?",
          "I'm new to wine. Explain {g} to me.", "Can you describe {g} for me?", "What are the key characteristics of {g}?"]
T_COMPARE = ["Which has more {f}, {a} or {b}?", "Compare the {f} of {a} and {b}.", "Is {a} or {b} higher in {f}?",
             "How does {a} compare with {b} for {f}?", "Rank {a} and {b} by {f}.",
             "Between {a} and {b}, which has the higher recorded {f} level?"]

single = [("body", T_BODY, 11, lambda p: ([f"body: {p['body']}"], f"body: {p['body']}")),
          ("acidity", T_ACID, 11, lambda p: ([f"acidity: {p['acidity']}"], f"acidity: {p['acidity']}")),
          ("tannin", T_TANN, 11, lambda p: ([f"tannin: {p['tannin']}"], f"tannin: {p['tannin']}")),
          ("aromas", T_AROMA, 11, lambda p: ([f"Aroma: {a}" for a in aromas(p)], "; ".join(f"Aroma: {a}" for a in aromas(p)))),
          ("ageing_potential", T_AGE, 11, lambda p: ([f"ageing_potential: {p['ageing_potential']}"], f"ageing_potential: {p['ageing_potential']}")),
          ("origin", T_ORIGIN, 11, lambda p: ([f"origin: {origin(p)}"], f"origin: {origin(p)}")),
          ("summary", T_SUMMARY, 11, lambda p: ([f"summary: {p['summary']}"], f"summary: {p['summary']}"))]
FIELD_OF = {"ageing_potential": "ageing", "aromas": "aromas", "summary": "summary"}

# full descriptions first, so they get the least-used grapes
for p, template in zip(pick("summary", "full_description", 12), rotate(T_FULL, 12)):
    required = [f"Summary meaning: {p['summary']}"]
    for label, field in (("Body", "body"), ("Acidity", "acidity"), ("Tannin level/texture", "tannin")):
        if has(p, field):
            required.append(f"{label}: {p[field]}")
    required += [f"Aroma: {a}" for a in aromas(p)]
    optional = ([f"Ageing: {p['ageing_potential']}"] if p["ageing_potential"] else []) + ([f"Origin: {origin(p)}"] if origin(p) else [])
    if p["key_regions"]:
        optional.append(f"Known regions: {p['key_regions']}")
    expected = " ".join(required + optional)
    add("supported", "full_description", p["grape"], template.format(g=p["grape"]), expected, "WINE_DISCUSSION", [p["grape"]], required, optional)

# comparisons: 5 acidity, 3 tannin, 3 body
for field, count in (("acidity", 5), ("tannin", 3), ("body", 3)):
    made = 0
    attempts = 0
    while made < count and attempts < 400:
        attempts += 1
        a, b = pick(field, "comparison", 2)
        la, lb = LEVEL[field](a), LEVEL[field](b)
        pair = frozenset((a["grape"], b["grape"]))
        if la == lb or pair in {frozenset(p) for _, p in v4_pairs if len(p) == 2}:
            for p in (a, b):
                usage[p["grape"]] -= 1
                used_pairs.discard(("comparison", p["grape"]))
            continue
        hi, lo = (a, b) if la > lb else (b, a)
        template = rng.choice(T_COMPARE)
        required = [f"{hi['grape']}: {hi[field]} {WORD[field]}", f"{lo['grape']}: {lo[field]} {WORD[field]}",
                    f"{hi['grape']} has higher recorded {WORD[field]}"]
        add("supported", "comparison", a["grape"], template.format(a=a["grape"], b=b["grape"], f=WORD[field]), "; ".join(required),
            "WINE_DISCUSSION", [a["grape"], b["grape"]], required, [], family=f"comparison-{field}-{a['grape']}-{b['grape']}")
        made += 1

for kind, templates, count, build in single:
    field = FIELD_OF.get(kind, kind)
    for p, template in zip(pick(field, kind, count), rotate(templates, count)):
        required, expected = build(p)
        add("supported", kind, p["grape"], template.format(g=p["grape"]), expected, "WINE_DISCUSSION", [p["grape"]], required, [])

# ---------------------------------------------------------------- false claims: 100
CLAIM = {"body": {0: "very light-bodied", 1: "light-bodied", 2: "medium-bodied", 3: "full-bodied", 4: "very full-bodied"},
         "acidity": {1: "low acidity", 2: "moderate acidity", 4: "high acidity", 5: "very high acidity"},
         "tannin": {0: "no tannins", 1: "low tannins", 2: "moderate tannins", 3: "firm tannins", 4: "very firm tannins"}}
FC_TEMPLATES = {
    "body": ["I read that {g} is {c}. Is that right?", "Would you say {g} is {c}?", "My friend insists {g} is {c}. Does that match your notes?",
             "Is it true that {g} is {c}?", "So {g} is {c}, yes?"],
    "acidity": ["I read that {g} has {c}. Is that right?", "Does {g} have {c}, according to your notes?", "Is it true that {g} has {c}?",
                "{g} has {c}, doesn't it?", "A sommelier told me {g} has {c}. Can you confirm?"],
    "tannin": ["I read that {g} has {c}. Is that right?", "Does {g} have {c}, according to your notes?", "Is it true that {g} has {c}?",
               "{g} has {c}, doesn't it?", "A sommelier told me {g} has {c}. Can you confirm?"]}

for field, count in (("body", 34), ("acidity", 33), ("tannin", 33)):
    kind = f"correct_{field}"
    pool = [p for p in profiles if has(p, field) and (field, (p["grape"],)) not in v4_pairs]
    templates = rotate(FC_TEMPLATES[field], count)
    chosen = pick(field, kind, count)
    for p, template in zip(chosen, templates):
        truth = LEVEL[field](p)
        options = [lv for lv in CLAIM[field] if abs(lv - truth) >= 2]
        claim = CLAIM[field][rng.choice(options)]
        question = template.format(g=p["grape"], c=claim)
        required = [f"Politely correct the false {field} claim", f"{field}: {p[field]}"]
        add("false_claim", kind, p["grape"], question, f"Not quite. {p['grape']}: {field} {p[field]}.", "WINE_DISCUSSION",
            [p["grape"]], required, [])

# lowercase grape names in about 15% of the answerable and false-claim questions, spread across types
eligible = [c for c in cases if c["category"] in ("supported", "false_claim") and c["question_type"] != "comparison"]
for case in rng.sample(eligible, 30):
    name = case["source_grapes"][0]
    case["question"] = lower_name(case["question"], name)
    case["casing"] = "lowercase_name"

# ---------------------------------------------------------------- unsupported: 100
grape_names = [p["grape"].lower() for p in rows] + [a for p in rows for a in re.split(r"[;,/]", p["also_known_as"].lower()) if a.strip()]
winery_text = WINERIES.read_text(errors="ignore").lower()


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


ONSET = ["b", "br", "c", "cl", "d", "dr", "f", "fl", "g", "gr", "k", "kr", "l", "m", "n", "p", "pr", "q", "r", "s", "sv", "t", "tr", "v", "vr", "z", "zh"]
VOWEL = ["a", "e", "i", "o", "u", "ae", "ia"]
CODA = ["", "n", "r", "l", "x", "th", "ss"]
v4_unknown = {c["question"].lower() for c in v4["cases"] if c["category"] == "no_information"}
names = []
while len(names) < 100:
    n = "".join(rng.choice(ONSET) + rng.choice(VOWEL) + rng.choice(CODA) for _ in range(rng.choice((2, 3))))
    if not 6 <= len(n) <= 10 or n in names or n in winery_text:
        continue
    if any(distance(n, g) < 3 for g in grape_names if abs(len(g) - len(n)) < 4):
        continue
    if any(n in q for q in v4_unknown):
        continue
    names.append(n)

T_UNKNOWN = ["Tell me about {n}.", "Which aromas does {n} have?", "What body would {n} have?", "Is there a tannin level for {n}?",
             "How acidic is {n}?", "Where does {n} come from?", "How long can {n} age?", "Describe {n} in plain English.",
             "What do your notes say about {n}?", "Give me a quick overview of {n}."]
bottle_cycle = rotate([p["grape"] for p in profiles], 100)
templates = rotate(T_UNKNOWN, 100)
for i, (name, template) in enumerate(zip(names, templates)):
    shown = name.capitalize() if i % 2 == 0 else name
    add("no_information", "unknown_subject", bottle_cycle[i], template.format(n=shown),
        "I'm sorry, I do not have that information.", "KOTLIN_REFUSAL", [], ["I'm sorry, I do not have that information."], [],
        grading="exact", family=f"unknown_subject-{bottle_cycle[i]}")
    if i % 2:
        cases[-1]["casing"] = "lowercase_name"

for number, case in enumerate(cases, 1):
    case["id"] = f"ASK5-{number:03d}"

# Unsupported questions that Kotlin's lookup did not refuse when this set was built (found by running every case through
# WineAskExtras.lookup). They reach Gemma, so they test Gemma's own decline. Re-check after any Kotlin lookup change.
MISSES = CSV_OUT.parent / "kotlin_lookup_misses.txt"
known_misses = set(MISSES.read_text().split()) if MISSES.exists() else set()
for case in cases:
    if case["id"] in known_misses:
        assert case["category"] == "no_information", case["id"]
        case["expected_kotlin_miss"] = True

# ---------------------------------------------------------------- checks
counts = Counter(c["category"] for c in cases)
types = Counter(c["question_type"] for c in cases)
assert len(cases) == 300 and counts == {"supported": 100, "false_claim": 100, "no_information": 100}, counts
questions = [c["question"].lower() for c in cases]
assert len(set(questions)) == 300, "duplicate questions"
assert not (set(questions) & v4_questions), "overlaps v4"
for c in cases:
    if c["category"] != "no_information":
        assert c["required_facts"] and all(g in by_name for g in c["source_grapes"]), c["id"]

meta = {"name": "Bottle detail ASK v5 — held-out, 300 questions", "version": 5, "entry": "bottle_detail_ask", "synthetic_bottles": True,
        "case_count": 300, "canonical_profile_count": len(profiles), "raw_profile_count": len(rows),
        "duplicate_alias_rows_excluded": [f"{a} → {b}" for a, b in ALIAS_ROWS.items()],
        "category_counts": dict(counts), "question_type_counts": dict(types), "seed": SEED, "planned_runs": 1,
        "knowledge_sha256": hashlib.sha256(KNOWLEDGE.read_bytes()).hexdigest(),
        "prompt_sha256": hashlib.sha256(PROMPT.read_bytes()).hexdigest(),
        "expected_kotlin_lookup_misses": len(known_misses),
        "pass_rule": "At least 90% of answers fully correct (grade 2), overall and in each of the three categories.",
        "rubric": v4["meta"]["rubric"],
        "notes": "Held-out set: do not tune the prompt or Kotlin against it. 100 answerable, 100 false claims, 100 unsupported, "
                 "equal thirds. New question text and new (type, grape) pairs relative to v4. Fresh conversation per case. "
                 "v4 showed near-identical output across repeat runs, so one run is planned. Profiles and keys are evaluation "
                 "metadata only, never passed to Gemma. Required facts frozen before the run."}
OUT.write_text(json.dumps({"meta": meta, "profiles": v4["profiles"], "cases": cases}, ensure_ascii=False, indent=2) + "\n")
CSV_OUT.parent.mkdir(parents=True, exist_ok=True)
with CSV_OUT.open("w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(["id", "category", "question_type", "casing", "bottle", "question", "expected_answer"])
    for c in cases:
        writer.writerow([c["id"], c["category"], c["question_type"], c.get("casing", ""), c["bottle"]["variety"], c["question"], c["expected_answer"][:200]])
print("categories", dict(counts))
print("types", dict(types))
grape_uses = Counter(g for c in cases if c["category"] != "no_information" for g in c["source_grapes"])
print("grapes used", len(grape_uses), "uses min/max", min(grape_uses.values()), max(grape_uses.values()))
print("unsupported questions Kotlin does not refuse:", len(known_misses))
print("lowercase-name questions", sum(1 for c in cases if c.get("casing")))
