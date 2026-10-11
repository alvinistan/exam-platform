# ADR-008: Immutable question versions and answer-key snapshots

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Editing a question must never change a completed attempt's meaning or score (S2 §5.3); scoring must not depend on catalog being up.

## Decision

Questions have versions; a version is frozen (database trigger) when published. Attempts store the version id plus a snapshot of the answer key, marks and misconception mapping. Display content is fetched by version id and cached indefinitely because frozen versions never change.

## Consequences

Some duplication of answer keys in assessment; edits to published questions create new versions (visible in history).

## Alternatives considered

Mutable questions with audit only (rejected: past results could silently change).
