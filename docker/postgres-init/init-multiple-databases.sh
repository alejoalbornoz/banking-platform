#!/bin/bash
# Runs once, on first container startup, before any application connects.
# Creates one database per service (database-per-service pattern) from a
# comma-separated list in POSTGRES_MULTIPLE_DATABASES, so a single Postgres
# container can back every microservice during local development while each
# service still only ever sees its own schema.
set -e
set -u

function create_database() {
    local database=$1
    echo "Creating database '$database'"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
        CREATE DATABASE $database;
        GRANT ALL PRIVILEGES ON DATABASE $database TO $POSTGRES_USER;
EOSQL
}

if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
    echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"
    IFS=',' read -ra DATABASES <<< "$POSTGRES_MULTIPLE_DATABASES"
    for db in "${DATABASES[@]}"; do
        create_database "$db"
    done
    echo "Multiple databases created"
fi
