#!/bin/sh
# Creates one database and one login role per service on first start of an empty volume (ADR-003).
# Each service owns only its own database; no role can connect to another service's database.
set -eu

create_db() {
  db="$1"; role="$2"; password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v role="$role" -v db="$db" -v pw="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'pw';
CREATE DATABASE :"db" OWNER :"role";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"role";
SQL
  # Each service's tables live in its own database; drop the default PUBLIC create right on schema public.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" -v role="$role" <<'SQL'
ALTER SCHEMA public OWNER TO :"role";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
}

create_db identity     identity_svc     "$IDENTITY_DB_PASSWORD"
create_db catalog      catalog_svc      "$CATALOG_DB_PASSWORD"
create_db assessment   assessment_svc   "$ASSESSMENT_DB_PASSWORD"
create_db learning     learning_svc     "$LEARNING_DB_PASSWORD"
create_db billing      billing_svc      "$BILLING_DB_PASSWORD"

# Read-only roles for the trial and main question banks (ADR-009). The catalog migration grants table access;
# catalog_svc may switch to either role for the free-access read path.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
CREATE ROLE catalog_trial_reader NOLOGIN;
CREATE ROLE catalog_main_reader NOLOGIN;
GRANT catalog_trial_reader, catalog_main_reader TO catalog_svc;
SQL
