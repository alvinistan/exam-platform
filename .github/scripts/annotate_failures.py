#!/usr/bin/env python3
"""Turn a failed Maven build into GitHub annotations, so failures are readable on the pull request page
without downloading logs. Usage: annotate_failures.py <maven-log> <backend-dir>"""
import glob
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 9  # GitHub shows at most 10 error annotations per step


def escape(text: str) -> str:
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def emit(title: str, body: str) -> None:
    print(f"::error title={escape(title)[:200]}::{escape(body[:60000])}")


def main() -> None:
    log_path, backend = sys.argv[1], sys.argv[2]
    emitted = 0

    # 1. Failed or errored tests, with message and the top of the stack trace.
    for report in sorted(glob.glob(f"{backend}/**/target/surefire-reports/TEST-*.xml", recursive=True)):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for case in root.iter('testcase'):
            for kind in ('failure', 'error'):
                node = case.find(kind)
                if node is None or emitted >= MAX_ANNOTATIONS:
                    continue
                trace = '\n'.join((node.text or '').strip().splitlines()[:40])
                emit(f"{case.get('classname')}.{case.get('name')} ({kind})",
                     f"{node.get('type')}: {node.get('message')}\n\n{trace}")
                emitted += 1

    # 2. Maven [ERROR] lines (compilation errors, dependency resolution, plugin failures).
    try:
        with open(log_path, encoding='utf-8', errors='replace') as f:
            errors = [line.rstrip() for line in f if '[ERROR]' in line]
    except FileNotFoundError:
        errors = []
    seen, unique = set(), []
    for line in errors:
        if line not in seen:
            seen.add(line)
            unique.append(line)
    if unique:
        emit('Maven [ERROR] output', '\n'.join(unique[:200]))
    elif emitted == 0:
        emit('Build failed', 'No [ERROR] lines or failed tests found; see the job log.')


if __name__ == '__main__':
    main()
