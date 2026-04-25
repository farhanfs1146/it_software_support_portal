CREATE TABLE ticket_history
(
    id           BIGINT PRIMARY KEY,
    action       VARCHAR(255) NOT NULL,
    old_value    VARCHAR(255),
    new_value    VARCHAR(255),
    action_time  TIMESTAMP    NOT NULL,

    ticket_id    BIGINT       NOT NULL,
    performed_by BIGINT       NOT NULL
);