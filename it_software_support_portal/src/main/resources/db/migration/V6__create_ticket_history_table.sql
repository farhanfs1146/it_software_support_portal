CREATE TABLE ticket_history
(
    id           BIGSERIAL PRIMARY KEY,
    action       VARCHAR(255) NOT NULL,
    old_value    VARCHAR(100),
    new_value    VARCHAR(100),
    action_time  TIMESTAMP    NOT NULL,

    ticket_id    BIGINT       NOT NULL,
    performed_by BIGINT       NOT NULL
);