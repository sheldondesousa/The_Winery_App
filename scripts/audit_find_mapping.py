#!/usr/bin/env python3
"""Audit compiled Find mappings and execute their real SQL against the read-only asset.
Run after :app:compileDebugKotlin with JAVA_HOME pointing to JDK 17 or newer.
Only reports in Docs/ are written; the database is opened read-only.
"""
import base64
import csv
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria;
import com.sheldondesousa.uncork.data.reviews.*;
public class FindMappingAudit {
    static void emit(String kind, String name, String... values) {
        var cells = new ArrayList<String>(); cells.add(kind); cells.add(name); cells.addAll(Arrays.asList(values));
        System.out.println(cells.stream().map(v -> Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))).reduce((a,b)->a+"\t"+b).orElse(""));
    }
    static void query(String key, String country, String province, Set<String> type,
            Set<String> tannin, Set<String> acidity, Set<String> body, Set<String> sweetness) {
        var result = GuidedReviewQuery.Companion.from(new GuidedCriteria(country, province, type, tannin, acidity, body, sweetness), false);
        var values = new ArrayList<String>(); values.add(result.getSql()); values.addAll(Arrays.asList(result.getArguments()));
        emit("query", key, values.toArray(new String[0]));
    }
    public static void main(String[] args) {
        var empty = Set.<String>of();
        var types = WineTypeVarietyMap.INSTANCE.getTYPE_TO_VARIETIES();
        // Body/Tannin/Acidity: plain label lists, matched against precomputed columns.
        var classified = new LinkedHashMap<String, List<String>>();
        classified.put("Tannin", FindPhraseEvidence.INSTANCE.getTANNIN_LABELS());
        classified.put("Acidity", FindPhraseEvidence.INSTANCE.getACIDITY_LABELS());
        classified.put("Body", FindPhraseEvidence.INSTANCE.getBODY_LABELS());
        // Sweetness: still review-text phrase evidence, no precomputed column.
        var sweetness = FindPhraseEvidence.INSTANCE.getSWEETNESS_EVIDENCE();
        types.forEach((key,values) -> {
            emit("type", key, values.toArray(new String[0]));
            query("Type/"+key,"","",Set.of(key),empty,empty,empty,empty);
        });
        classified.forEach((field,labels) -> labels.forEach(key -> {
            emit("label", field+"/"+key);
            query(field+"/"+key,"","",empty,field.equals("Tannin")?Set.of(key):empty,
                field.equals("Acidity")?Set.of(key):empty,field.equals("Body")?Set.of(key):empty,
                empty);
        }));
        sweetness.forEach((key,values) -> {
            emit("evidence","Sweetness/"+key,values.toArray(new String[0]));
            query("Sweetness/"+key,"","",empty,empty,empty,empty,Set.of(key));
        });
        query("Combined","France","Bordeaux",Set.of("Red","White"),Set.of("Smooth","Moderate"),empty,Set.of("Full-Bodied"),empty);
        query("CaseInsensitive","france","bordeaux",Set.of("Red"),empty,empty,empty,empty);
        query("QuotedCountry","France' OR 1=1 --","",empty,empty,empty,empty,empty);
        query("TypeUnion","","",Set.of("Red","White"),empty,empty,empty,empty);
        query("RankFixture","France","Bordeaux",Set.of("Red"),empty,empty,empty,empty);
        query("TanninUnion","","",empty,Set.of("Smooth","Moderate"),empty,empty,empty);
    }
}
'''

def main():
    java_home = Path(os.environ['JAVA_HOME'])
    classes = ROOT / 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
    jars = [p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').glob('*/*/*.jar') if not p.name.endswith('-sources.jar')]
    stdlib = sorted(jars, key=lambda p: p.stat().st_mtime)[-1]
    with tempfile.TemporaryDirectory(prefix='find-mapping-audit-') as temp:
        path = Path(temp)
        (path/'FindMappingAudit.java').write_text(JAVA)
        cp = os.pathsep.join([str(classes), str(stdlib), temp])
        subprocess.run([str(java_home/'bin/javac'), '-cp', cp, str(path/'FindMappingAudit.java')], check=True)
        output = subprocess.check_output([str(java_home/'bin/java'), '-cp', cp, 'FindMappingAudit'], text=True)
    entries = [[base64.b64decode(c).decode() for c in line.split('\t')] for line in output.splitlines()]
    types = {e[1]: e[2:] for e in entries if e[0]=='type'}
    labels: dict[str, list[str]] = {}
    for e in entries:
        if e[0] == 'label':
            field, key = e[1].split('/', 1)
            labels.setdefault(field, []).append(key)
    evidence = {e[1]: e[2:] for e in entries if e[0]=='evidence'}
    queries = {e[1]: (e[2],e[3:]) for e in entries if e[0]=='query'}
    db = sqlite3.connect(f'file:{ROOT}/app/src/main/assets/wine_reviews.db?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    inventory = dict(db.execute('SELECT variety,COUNT(*) FROM wine_reviews GROUP BY variety'))
    classified_fields = {'Tannin': 'tannin', 'Acidity': 'acidity', 'Body': 'body'}
    report = ['# Find mapping data audit', '', 'Generated from compiled application mappings and queries using `scripts/audit_find_mapping.py`. Database opened read-only.', '', '## Type coverage', '', '| Type | Exact variety values | Matching rows |', '|---|---:|---:|']
    mapped = set()
    for name, values in types.items():
        assert values and all(v in inventory for v in values), (name, values)
        assert not mapped.intersection(values), 'Type groups should not accidentally duplicate variety values'
        mapped.update(values)
        report.append(f'| {name} | {len(values)} | {sum(inventory[v] for v in values):,} |')
    unmapped = sorted(set(inventory)-mapped, key=str.casefold)
    report += ['', f'Mapped {len(mapped)} of {len(inventory)} distinct variety values and {sum(inventory[v] for v in mapped):,} of {sum(inventory.values()):,} rows. The remaining {len(unmapped)} variety values are **unclassified**, not absent from the database. They remain searchable when Type is unselected. See [unclassified inventory](Find-Unclassified-Varieties.csv).', '', '## Classified column evidence (Tannin/Acidity/Body)', '', 'These fields match the precomputed `tannin`/`acidity`/`body` columns directly, not review text.', '', '| Field / option | Matching rows |', '|---|---:|']
    for field, keys in labels.items():
        column = classified_fields[field]
        for key in keys:
            count = db.execute(f'SELECT COUNT(*) FROM wine_reviews WHERE {column}=? COLLATE NOCASE', (key,)).fetchone()[0]
            assert count > 0, (field, key)
            report.append(f'| {field}/{key} | {count:,} |')
    report += ['', '## Phrase evidence (Sweetness)', '', 'Sweetness has no precomputed column, so it still matches `review_summary` substrings.', '', '| Field / option | Phrase | Matching rows |', '|---|---|---:|']
    for name, phrases in evidence.items():
        for phrase in phrases:
            count = db.execute('SELECT COUNT(*) FROM wine_reviews WHERE review_summary LIKE ? COLLATE NOCASE', (f'%{phrase}%',)).fetchone()[0]
            assert count > 0, (name, phrase)
            report.append(f'| {name} | {phrase} | {count:,} |')
    for name, (sql, args) in queries.items():
        rows = db.execute(sql,args).fetchall()
        assert len(rows) <= 3
        if name == 'QuotedCountry': assert not rows
        elif name != 'RankFixture': assert rows, name
        if name.startswith('Type/'):
            assert all(r['variety'] in types[name.split('/')[1]] for r in rows)
        if '/' in name:
            field, key = name.split('/', 1)
            if field in classified_fields:
                assert all(r[classified_fields[field]] == key for r in rows)
            elif name in evidence:
                assert all(any(p.lower() in r['review_summary'].lower() for p in evidence[name]) for r in rows)
        if name == 'Combined':
            assert all(r['country']=='France' and r['province']=='Bordeaux' and r['variety'] in types['Red']+types['White'] and
                r['tannin'] in ('Smooth', 'Moderate') and r['body'] == 'Full-Bodied' for r in rows)
    fixture = sqlite3.connect(':memory:')
    fixture.executescript('CREATE TABLE wine_reviews (id INTEGER, country TEXT, province TEXT, variety TEXT, points INTEGER, winery TEXT, review_summary TEXT, name TEXT, body TEXT, tannin TEXT, acidity TEXT);')
    for row in [(1, None, 'A', 'Moderate'), (2, 90, 'B', 'Smooth'), (3, 90, 'B', 'Moderate'), (4, 0, 'Z', None)]:
        fixture.execute("INSERT INTO wine_reviews VALUES (?, 'France', 'Bordeaux', 'Merlot', ?, ?, 'a review', 'Sparkling Rosé', NULL, ?, NULL)",row)
    fixture.execute("INSERT INTO wine_reviews VALUES (5,'Italy','Tuscany','Chardonnay',100,'A','a review','Cabernet Sauvignon Port',NULL,NULL,'Crisp')")
    def ids(key):
        sql,args=queries[key]
        return [r[0] for r in fixture.execute(sql,args)]
    assert ids('RankFixture') == [2,3,4], 'Rank by score, winery, id with null points last'
    assert ids('Type/Sparkling') == [] and ids('Type/Rosé') == [] and ids('Type/Fortified') == [], 'Names must not determine Type'
    assert ids('TypeUnion') == [5,2,3]
    assert ids('TanninUnion') == [2,3,1], 'Selected levels must be OR, not AND'
    assert ids('Tannin/Astringent') == [], 'Rows 1 and 3 are Moderate, not Astringent'
    assert ids('Acidity/Tart') == [], 'Row 5 is Crisp, not Tart'
    assert ids('Body/Full-Bodied') == [], 'No fixture row is classified Full-Bodied'
    with (ROOT/'Docs/Find-Unclassified-Varieties.csv').open('w',newline='') as f:
        writer=csv.writer(f);writer.writerow(['variety','rows']);writer.writerows((v,inventory[v]) for v in unmapped)
    report += ['', '## Executed query checks', '', f'Passed {len(queries)} compiled-query executions on the bundled database plus synthetic checks for OR/AND behavior, exact Type membership, misleading wine names, ambiguous phrases, case-insensitive geography, quoted user input, ranking, and null scores.', '', 'Counts establish data presence, not professional validation of wine classification or phrase accuracy. Substring evidence may still misread context or negation.']
    (ROOT/'Docs/Find-Mapping-Audit.md').write_text('\n'.join(report)+'\n')
    print(f'Audited {len(mapped)} mapped varieties, {sum(map(len,labels.values()))} classified labels, '
          f'{sum(map(len,evidence.values()))} sweetness phrases, {len(queries)} compiled SQL queries and synthetic regression cases.')

if __name__ == '__main__':
    main()
