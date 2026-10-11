# ADR-013: First production deployment on a single EC2 host

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Start simple (master spec §19); Kubernetes only when justified.

## Decision

One EC2 instance (Ubuntu LTS) runs the gateway, services and web app with Docker Compose behind Caddy or Nginx for TLS. PostgreSQL runs on Amazon RDS (recommended) for automated backups and point-in-time recovery; a self-managed PostgreSQL container with EBS snapshots is the lower-cost alternative. Separate staging and production. Images built and scanned in GitHub Actions, pulled by tag; rollback = previous tag.

## Consequences

Single host is a single point of failure (target 99.5%); acceptable for launch. Next step: a second instance behind a load balancer, then ECS or Kubernetes.

## Alternatives considered

Kubernetes now (rejected: cost and operational load before there is traffic). RDS vs container is pending the client's budget.
