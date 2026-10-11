#!/usr/bin/env bash
# Creates the five service databases exactly as Docker Compose does, applies each service's migrations as that
# service's own login (proving it has the privileges it needs and no more), then runs the schema checks.
#
# Usage (against an empty PostgreSQL 16 server):
#   PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=... backend/db-tests/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

export POSTGRES_USER="${PGUSER:-postgres}"
export IDENTITY_DB_PASSWORD=dbtest-identity CATALOG_DB_PASSWORD=dbtest-catalog \
       ASSESSMENT_DB_PASSWORD=dbtest-assessment LEARNING_DB_PASSWORD=dbtest-learning BILLING_DB_PASSWORD=dbtest-billing

echo "== creating databases and roles"
sh "$repo/infra/compose/postgres-init/01-databases.sh" >/dev/null

migrate() { # service database role password
  local dir="$repo/backend/services/$1/src/main/resources/db/migration"
  for f in "$dir"/V*.sql; do
    PGUSER="$3" PGPASSWORD="$4" psql -q -v ON_ERROR_STOP=1 -d "$2" -f "$f" >/dev/null
    echo "migrated $2 with $(basename "$f") as $3"
  done
}
migrate identity-service     identity   identity_svc   "$IDENTITY_DB_PASSWORD"
migrate catalog-service      catalog    catalog_svc    "$CATALOG_DB_PASSWORD"
migrate assessment-service   assessment assessment_svc "$ASSESSMENT_DB_PASSWORD"
migrate learning-service     learning   learning_svc   "$LEARNING_DB_PASSWORD"
migrate subscription-service billing    billing_svc    "$BILLING_DB_PASSWORD"

echo "== isolation: a service login cannot open another service's database"
if PGUSER=identity_svc PGPASSWORD="$IDENTITY_DB_PASSWORD" psql -d billing -c 'select 1' >/dev/null 2>&1; then
  echo "FAIL  identity_svc could connect to billing"; exit 1
else
  echo "PASS  identity_svc denied on billing"
fi

echo "== schema checks"
"$here/schema_checks.sh"
