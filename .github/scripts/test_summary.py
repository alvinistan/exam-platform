#!/usr/bin/env python3
"""Emit one notice annotation with test counts per Maven module (readable on the pull request)."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

backend = sys.argv[1] if len(sys.argv) > 1 else 'backend'
totals = {}
for report in glob.glob(f"{backend}/**/target/surefire-reports/TEST-*.xml", recursive=True):
    module = report.split('/target/')[0].replace(backend + '/', '')
    root = ET.parse(report).getroot()
    t = totals.setdefault(module, [0, 0, 0, 0])
    t[0] += int(root.get('tests', 0)); t[1] += int(root.get('failures', 0))
    t[2] += int(root.get('errors', 0)); t[3] += int(root.get('skipped', 0))
lines = [f"{m}: {t[0]} tests, {t[1]} failures, {t[2]} errors, {t[3]} skipped" for m, t in sorted(totals.items())]
total = sum(t[0] for t in totals.values())
body = '\n'.join(lines + [f"TOTAL: {total} tests"]).replace('%', '%25').replace('\n', '%0A')
print(f"::notice title=Backend tests ({total})::{body}")
