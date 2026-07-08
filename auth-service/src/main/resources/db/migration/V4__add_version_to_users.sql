-- Tabla de users
ALTER TABLE users
    ADD COLUMN version BIGINT;

UPDATE users SET version = 1;

ALTER TABLE users
    ALTER COLUMN version SET NOT NULL;
