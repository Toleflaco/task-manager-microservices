CREATE TABLE refresh_tokens (
                                id           BIGSERIAL    PRIMARY KEY,
                                token        VARCHAR(36)  NOT NULL UNIQUE,
                                user_id      BIGINT       NOT NULL,
                                family_id    UUID         NOT NULL,
                                expires_at   TIMESTAMPTZ  NOT NULL,
                                revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
                                created_at   TIMESTAMPTZ  NOT NULL,
                                CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
                                    REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);