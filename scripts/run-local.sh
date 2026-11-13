#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo "Checking local PostgreSQL on localhost:5432..."
if command -v pg_isready >/dev/null 2>&1; then
  if ! pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
    echo "Local PostgreSQL is not reachable on 127.0.0.1:5432. Start your PostgreSQL service and retry." >&2
    exit 1
  fi
elif ! (echo >/dev/tcp/127.0.0.1/5432) >/dev/null 2>&1; then
  echo "Local PostgreSQL is not reachable on 127.0.0.1:5432. Start your PostgreSQL service and retry." >&2
  exit 1
fi

echo "Starting LawRAG with the local profile on http://localhost:8080 ..."
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
