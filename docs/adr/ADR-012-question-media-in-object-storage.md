# ADR-012: Question media in object storage

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Questions and options may include images (max 2 MB).

## Decision

Images are uploaded through catalog-service, type-sniffed, re-encoded (strips metadata and payloads) and stored in S3 (MinIO locally) under random keys. Clients receive short-lived signed URLs; premium question media is only signed for entitled requests.

## Consequences

No public bucket; media is as protected as the question text.

## Alternatives considered

Storing images in PostgreSQL (rejected: database bloat, backup size).
