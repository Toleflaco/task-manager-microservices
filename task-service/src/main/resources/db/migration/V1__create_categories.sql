CREATE TABLE categories (
                            id          BIGSERIAL    PRIMARY KEY,
                            name        TEXT         NOT NULL,
                            description TEXT,
    -- Logical FK to auth_schema.users.
    -- Referential integrity enforced at application layer via JWT claims.
                            user_id     BIGINT       NOT NULL,
                            created_at  TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ix_categories_user_name_lower
    ON categories (user_id, LOWER(name));
