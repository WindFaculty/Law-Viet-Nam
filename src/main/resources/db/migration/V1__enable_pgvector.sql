-- Phase 1 verification: Flyway must run against a pgvector-enabled PostgreSQL.
-- The vector extension is created here once; later migrations add tables/indexes.
CREATE EXTENSION IF NOT EXISTS vector;
