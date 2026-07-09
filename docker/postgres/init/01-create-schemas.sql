-- Init script executed on first Postgres container startup.
-- Runs only when postgres-data volume is empty.
-- Creates the schema-per-service topology for the microservices system.

CREATE SCHEMA IF NOT EXISTS auth_schema;
CREATE SCHEMA IF NOT EXISTS task_schema;
