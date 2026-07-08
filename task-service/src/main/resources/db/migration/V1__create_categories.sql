CREATE TABLE categories (
                            id          BIGSERIAL    PRIMARY KEY,
                            name        TEXT         NOT NULL,
                            description TEXT,
                            user_id     BIGINT       NOT NULL,
                            created_at  TIMESTAMPTZ  NOT NULL,
                            CONSTRAINT fk_categories_user FOREIGN KEY (user_id)
                                REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ix_categories_user_name_lower
    ON categories (user_id, LOWER(name));