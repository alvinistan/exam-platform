#!/usr/bin/env python3
"""Prints the k6 load test's key numbers as a notice on the pull request and in the job summary."""
import json
import os
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
m = data.get("metrics", {})


def p(metric, key):
    v = m.get(metric, {}).get(key)
    return "n/a" if v is None else f"{v:.0f} ms"


reqs = m.get("http_reqs", {})
failed = m.get("http_req_failed", {}).get("value")
checks = m.get("checks", {})
iterations = m.get("iterations", {}).get("count", 0)
lines = [
    f"Mock exams completed: {iterations}",
    f"HTTP requests: {reqs.get('count', 0)} ({reqs.get('rate', 0):.1f}/s), failed: {0 if failed is None else failed * 100:.2f}%",
    f"Checks passed: {checks.get('passes', 0)} of {checks.get('passes', 0) + checks.get('fails', 0)}",
    f"Start mock: p95 {p('mock_start_ms', 'p(95)')}, max {p('mock_start_ms', 'max')}",
    f"Save answer: p95 {p('mock_answer_ms', 'p(95)')}, max {p('mock_answer_ms', 'max')}",
    f"Submit and mark: p95 {p('mock_submit_ms', 'p(95)')}, max {p('mock_submit_ms', 'max')}",
]
print("::notice title=Load test (mock exams)::" + " | ".join(lines))
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as f:
        f.write("### Load test (mock exams)\n\n" + "\n".join(f"- {l}" for l in lines) + "\n")
