#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER is required}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"
: "${POSTGRES_MIGRATOR_USER:?POSTGRES_MIGRATOR_USER is required}"
: "${POSTGRES_MIGRATOR_PASSWORD:?POSTGRES_MIGRATOR_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=app_user="$POSTGRES_APP_USER" \
  --set=app_password="$POSTGRES_APP_PASSWORD" \
  --set=migrator_user="$POSTGRES_MIGRATOR_USER" \
  --set=migrator_password="$POSTGRES_MIGRATOR_PASSWORD" \
  --set=db_name="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'migrator_user') \gexec

SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password') \gexec

SELECT format('CREATE SCHEMA transaction_schema AUTHORIZATION %I', :'migrator_user')
WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'transaction_schema') \gexec

SELECT format('GRANT CREATE ON DATABASE %I TO %I', :'db_name', :'migrator_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA transaction_schema TO %I', :'app_user') \gexec
SELECT format('ALTER ROLE %I SET search_path = transaction_schema, public', :'app_user') \gexec
SELECT format('ALTER ROLE %I SET search_path = transaction_schema, public', :'migrator_user') \gexec

SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA transaction_schema GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
  :'migrator_user', :'app_user') \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA transaction_schema GRANT USAGE, SELECT ON SEQUENCES TO %I',
  :'migrator_user', :'app_user') \gexec
SQL
