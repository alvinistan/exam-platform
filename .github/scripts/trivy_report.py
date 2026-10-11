#!/usr/bin/env python3
"""Summarise a Trivy JSON report as GitHub annotations and fail on fixable CRITICAL findings.
Usage: trivy_report.py <report.json> <image-name>"""
import json
import sys


def esc(text: str) -> str:
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main() -> int:
    path, image = sys.argv[1], sys.argv[2]
    try:
        with open(path, encoding='utf-8') as f:
            report = json.load(f)
    except (OSError, ValueError) as e:
        print(f"::error title=Trivy report missing for {image}::{esc(str(e))}")
        return 1

    rows = []
    for result in report.get('Results') or []:
        for v in result.get('Vulnerabilities') or []:
            if v.get('Severity') in ('CRITICAL', 'HIGH'):
                rows.append((v['Severity'], v['VulnerabilityID'], v.get('PkgName', '?'),
                             v.get('InstalledVersion', '?'), v.get('FixedVersion') or 'no fix yet',
                             result.get('Target', '?')))
    rows.sort()
    blocking = [r for r in rows if r[0] == 'CRITICAL' and r[4] != 'no fix yet']
    text = '\n'.join(f"{s} {vid} {pkg} {inst} -> {fix} ({target})" for s, vid, pkg, inst, fix, target in rows[:150])
    level = 'error' if blocking else 'notice'
    title = f"{image}: {len(blocking)} fixable CRITICAL, {len(rows)} HIGH/CRITICAL total"
    print(f"::{level} title={esc(title)}::{esc(text or 'No HIGH or CRITICAL vulnerabilities.')}")
    return 1 if blocking else 0


if __name__ == '__main__':
    sys.exit(main())
