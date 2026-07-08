-- Tabla de tasks
ALTER TABLE tasks
    ADD COLUMN deleted_at TIMESTAMPTZ;

-- Tabla de categories
ALTER TABLE categories
    ADD COLUMN deleted_at TIMESTAMPTZ;