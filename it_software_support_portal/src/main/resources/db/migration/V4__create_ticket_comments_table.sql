CREATE TABLE ticket_comments
(
    id           BIGSERIAL PRIMARY KEY,
    comment      TEXT      NOT NULL,
    created_at   TIMESTAMP NOT NULL,

    ticket_id    BIGINT    NOT NULL,
    commented_by BIGINT    NOT NULL
);