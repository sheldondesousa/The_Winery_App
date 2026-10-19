"""Pass/fail and answer-vs-decline scoring from who wrote the text the user sees (output_source), not from the route.

The route only says where a question was sent. The app can still replace Gemma's answer afterwards (the reply check's
refusal) or answer with the wrong thing (a winery list for a grape name). This reads a run log and recomputes both.

Usage:  python3 score_output_source.py [path/to/combined.jsonl]
Writes output_source_status.csv and output_source_summary.json next to the input file.
"""
import csv
import json
import sys
from pathlib import Path

NO_INFORMATION = "I'm sorry, I do not have that information."
GEMMA_SOURCES = {"GEMMA", "GEMMA_TIDIED"}
# Kotlin wrote this from the notes themselves; it is a real answer, so it counts as answered in the decision metric.
ANSWER_SOURCES = GEMMA_SOURCES | {"KOTLIN_FACTS_FALLBACK"}

default = Path(__file__).resolve().parent / "100Q Grape Profile - Evals " / "3x100 Combined.jsonl"
source = Path(sys.argv[1]) if len(sys.argv) > 1 else default
rows = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]


def reason(row):
    src, shown = row["output_source"], row["actual_answer"]
    if row["expected_route"] == "WINE_DISCUSSION":
        if src in GEMMA_SOURCES:
            return ""
        if src == "KOTLIN_OPINION_REFUSAL":
            return "Reply check replaced Gemma's answer with the refusal"
        if src == "KOTLIN_SCRIPT_REFUSAL":
            return "Reply check replaced Gemma's answer (foreign script)"
        if src == "KOTLIN_FACTS_FALLBACK":
            return "Reply check replaced Gemma's answer with the notes' first sentences"
        if shown != NO_INFORMATION:
            return "Kotlin replied before Gemma was asked (winery list, not the grape answer)"
        return "Kotlin refused before Gemma was asked"
    return "" if src not in GEMMA_SOURCES else "Gemma answered a question that should be declined"


def new_status(row):
    shown_by_gemma = row["output_source"] in GEMMA_SOURCES
    should_answer = row["expected_route"] == "WINE_DISCUSSION"
    source_ok = shown_by_gemma if should_answer else not shown_by_gemma
    if not row["route_matches"] or not source_ok:
        return "FAIL"
    if row["grading"] != "exact":
        return "REVIEW"
    return "PASS" if row["actual_answer"] == row["expected_answer"] else "FAIL"


def answered(row):
    return row["output_source"] in ANSWER_SOURCES


out = []
for row in rows:
    out.append(dict(
        id=row["id"], run_index=row["run_index"], category=row["category"], expected_route=row["expected_route"],
        actual_route=row["actual_route"], output_source=row["output_source"],
        shown_by="GEMMA" if row["output_source"] in GEMMA_SOURCES else "KOTLIN",
        old_status=row["status"], new_status=new_status(row), reason=reason(row),
        shown=row["actual_answer"][:120]))

summary = {"metric": "Answer vs decline, judged from the text the user sees", "positive_class": "Question that should be answered",
           "source_file": source.name, "runs": {}}
for run in sorted({r["run_index"] for r in rows}):
    subset = [r for r in rows if r["run_index"] == run]
    tp = sum(r["expected_route"] == "WINE_DISCUSSION" and answered(r) for r in subset)
    fn = sum(r["expected_route"] == "WINE_DISCUSSION" and not answered(r) for r in subset)
    fp = sum(r["expected_route"] != "WINE_DISCUSSION" and answered(r) for r in subset)
    tn = sum(r["expected_route"] != "WINE_DISCUSSION" and not answered(r) for r in subset)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    old_fail = sum(r["status"] == "FAIL" for r in subset)
    new_fail = [o for o in out if o["run_index"] == run and o["new_status"] == "FAIL"]
    summary["runs"][str(run)] = dict(
        cases=len(subset), tp=tp, fp=fp, fn=fn, tn=tn, accuracy=(tp + tn) / len(subset), precision=precision, recall=recall, f1=f1,
        route_only_fail_count=old_fail, output_source_fail_count=len(new_fail),
        output_source_fail_ids=[o["id"] for o in new_fail],
        fail_reasons={o["id"]: o["reason"] for o in new_fail})

base = source.parent
with (base / "output_source_status.csv").open("w", newline="") as handle:
    writer = csv.DictWriter(handle, fieldnames=list(out[0]))
    writer.writeheader()
    writer.writerows(out)
(base / "output_source_summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
print(json.dumps({k: {m: v for m, v in r.items() if m != "fail_reasons"} for k, r in summary["runs"].items()}, indent=2, ensure_ascii=False))
