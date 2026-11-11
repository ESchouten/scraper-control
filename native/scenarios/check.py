#!/usr/bin/env python3
"""Fails when one of the apps has no test for a scenario (or a test for an unknown one)."""
import json, re, sys, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
ids = [s["id"] for s in json.loads((root / "scenarios/scenarios.json").read_text())["scenarios"]]
compose = (root / "composeApp/src/jvmTest/kotlin/nl/eschouten/scraper/app/ScenarioTest.kt").read_text()
swift = (root / "iosApp/UITests/ScenarioTests.swift").read_text()
have_c = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", compose))
have_s = set(re.findall(r"func\s+test_(\w+)\s*\(", swift))
bad = False
for name, have in (("Compose", have_c), ("iOS", have_s)):
    missing = [i for i in ids if i not in have]
    extra = sorted(h for h in have if h not in ids and not h.startswith("screenshot"))
    if missing:
        bad = True
        print(f"{name}: missing scenarios: {', '.join(missing)}")
    if extra:
        bad = True
        print(f"{name}: tests not in scenarios.json: {', '.join(extra)}")
# One version for every app: gradle.properties (Android, About row) = project.yml (iOS).
gradle_v = re.search(r"^scraper\.version=(\S+)", (root / "gradle.properties").read_text(), re.M).group(1)
ios_v = set(re.findall(r"MARKETING_VERSION:\s*(\S+)", (root / "iosApp/project.yml").read_text()))
if ios_v != {gradle_v}:
    bad = True
    print(f"version: gradle.properties {gradle_v}, iosApp/project.yml {', '.join(sorted(ios_v))}")
print("scenarios: " + ("MISMATCH" if bad else f"both apps cover all {len(ids)}, version {gradle_v}"))
sys.exit(1 if bad else 0)
