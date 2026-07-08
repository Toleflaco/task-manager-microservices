-- Tabla de tasks
ALTER TABLE tasks
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE tasks SET updated_at = created_at;

ALTER TABLE tasks
    ALTER COLUMN updated_at SET NOT NULL;

-- Tabla de categories
ALTER TABLE categories
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE categories SET updated_at = created_at;

ALTER TABLE categories
    ALTER COLUMN updated_at SET NOT NULL;
