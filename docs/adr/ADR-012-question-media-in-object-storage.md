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

## Amendment (Phase 7, 12 Oct 2026)

Implemented behind a `MediaStorage` port. Release 1 stores images on the service's own volume (`/data/media`,
the `catalogmedia` Docker volume), which fits the single-host deployment of ADR-013; an S3 implementation can
replace it without changing callers when hosting moves. Instead of S3 presigned URLs, catalog-service signs its own
short-lived links (HMAC-SHA256 over key and expiry, key from `CATALOG_MEDIA_SIGNING_KEY`) and serves the bytes at
`/api/media/{key}`, with `nosniff`, a sandboxing CSP and private caching. Uploads are type-checked by their bytes,
limited to 2 MB and 4096 × 4096 pixels, decoded and re-encoded (PNG, or JPEG for photos; WebP becomes PNG).
MinIO is not used: no extra service is needed locally.
