-- Tabla de tasks
ALTER TABLE tasks
    ADD COLUMN version BIGINT;

UPDATE tasks SET version = 1;

ALTER TABLE tasks
    ALTER COLUMN version SET NOT NULL;

-- Tabla de categories
ALTER TABLE categories
    ADD COLUMN version BIGINT;

UPDATE categories SET version = 1;

ALTER TABLE categories
    ALTER COLUMN version SET NOT NULL;
