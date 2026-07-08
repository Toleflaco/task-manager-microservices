-- V1: Tabla de usuarios.
--
-- Decisiones:
-- - id: BIGSERIAL (BIGINT + secuencia auto-incremental). Mapea a
--   Long en Java con @GeneratedValue(strategy = IDENTITY).
-- - name: TEXT NOT NULL. Sin límite de longitud por negocio.
--   NOT NULL refleja @NotBlank en RegisterRequest.
-- - email: VARCHAR(254) por el límite del RFC 5321. Unicidad
--   case-insensitive vía índice funcional LOWER(email), garantía
--   real a nivel BD (race-condition safe).
-- - password: VARCHAR(72). BCrypt hash siempre 60 chars; margen
--   por si el algoritmo o el formato cambian en el futuro.
-- - created_at: TIMESTAMPTZ (TIMESTAMP WITH TIME ZONE). El valor
--   lo asigna la aplicación con OffsetDateTime.now(UTC). Sin
--   DEFAULT en BD para mantener trazabilidad explícita.

CREATE TABLE users (
                       id         BIGSERIAL    PRIMARY KEY,
                       name       TEXT         NOT NULL,
                       email      VARCHAR(254) NOT NULL,
                       password   VARCHAR(72)  NOT NULL,
                       created_at TIMESTAMPTZ  NOT NULL
);

-- Índice funcional para unicidad case-insensitive de email.
-- Cubre la lección de Fase 5.5: race conditions en existsByEmail
-- imposibles a nivel BD; el INSERT duplicado falla con
-- 23505 (unique_violation) y se traduce a 409 Conflict en app.
CREATE UNIQUE INDEX ix_users_email_lower ON users (LOWER(email));