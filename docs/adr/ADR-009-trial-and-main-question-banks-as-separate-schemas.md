# ADR-009: Trial and main question banks as separate schemas

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Requirement FR-QB-10: free access must never reach premium questions.

## Decision

catalog database has schemas bank_trial and bank_main created by one function, so their structure cannot drift. Database roles catalog_trial_reader and catalog_main_reader limit which schema a query path can read; the free path runs as the trial role. Moving a question between banks copies it and archives the original.

## Consequences

A bug in application code still cannot leak main-bank rows on the trial path (tested: permission denied). Two sets of tables to migrate together.

## Alternatives considered

Single table with a bank column and row-level security (viable; schemas chosen because the requirement names separate storage).
