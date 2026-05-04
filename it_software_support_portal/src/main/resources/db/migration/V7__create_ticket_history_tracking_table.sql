CREATE TABLE ticket_history_tracking
(
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    field_name  VARCHAR(100),
    old_value   TEXT,
    new_value   TEXT,
    changed_by  BIGINT       NOT NULL,
    remarks     TEXT,
    changed_at  TIMESTAMP    NOT NULL
);