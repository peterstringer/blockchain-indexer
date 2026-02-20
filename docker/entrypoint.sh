#!/bin/sh
set -e

echo "=============================================="
echo " Multi-Chain Block Indexer"
echo "=============================================="

# Wait for PostgreSQL if DATABASE_URL is set
if [ -n "$DATABASE_URL" ]; then
  # Extract host and port from JDBC URL
  # Format: jdbc:postgresql://host:port/dbname
  DB_HOST=$(echo "$DATABASE_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
  DB_PORT=$(echo "$DATABASE_URL" | sed -n 's|.*://[^:]*:\([0-9]*\).*|\1|p')
  DB_PORT=${DB_PORT:-5432}

  echo "Waiting for PostgreSQL at ${DB_HOST}:${DB_PORT}..."

  MAX_RETRIES=30
  RETRY_COUNT=0
  while ! nc -z "$DB_HOST" "$DB_PORT" 2>/dev/null; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ "$RETRY_COUNT" -ge "$MAX_RETRIES" ]; then
      echo "ERROR: PostgreSQL not available after ${MAX_RETRIES} attempts"
      exit 1
    fi
    echo "  Attempt ${RETRY_COUNT}/${MAX_RETRIES} - waiting 2s..."
    sleep 2
  done

  echo "PostgreSQL is ready!"
fi

echo "Starting application..."
echo "  JAVA_OPTS: ${JAVA_OPTS}"
echo "=============================================="

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar app.jar "$@"
