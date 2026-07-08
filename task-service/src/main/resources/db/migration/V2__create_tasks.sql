CREATE TABLE tasks
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    status       VARCHAR(16)  NOT NULL,
    priority     VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    due_date     TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    -- Logical FK to auth_schema.users.
    -- Referential integrity enforced at application layer via JWT claims.
    user_id      BIGINT       NOT NULL,
    category_id  BIGINT,
    CONSTRAINT fk_tasks_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_tasks_completed_after_created
        CHECK (completed_at IS NULL OR completed_at >= created_at),
    CONSTRAINT chk_tasks_due_after_created
        CHECK (due_date IS NULL OR due_date >= created_at)
);

CREATE INDEX ix_tasks_user_category ON tasks (user_id, category_id);
CREATE INDEX ix_tasks_user_status ON tasks (user_id, status);
