-- Tabla de tasks
ALTER TABLE tasks
    ADD COLUMN created_by BIGINT,
    ADD COLUMN last_modified_by BIGINT;

UPDATE tasks
SET created_by       = user_id,
    last_modified_by = user_id;

ALTER TABLE tasks
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN last_modified_by SET NOT NULL;


-- Tabla de categories
ALTER TABLE categories
    ADD COLUMN created_by BIGINT,
    ADD COLUMN last_modified_by BIGINT;

UPDATE categories
SET created_by       = user_id,
    last_modified_by = user_id;

ALTER TABLE categories
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN last_modified_by SET NOT NULL;