CREATE TABLE tickets
(
    id              BIGINT PRIMARY KEY,
    ticket_number   VARCHAR(100) UNIQUE NOT NULL,
    title           VARCHAR(255)        NOT NULL,
    description     TEXT                NOT NULL,
    issue_type      VARCHAR(50)         NOT NULL,
    priority        VARCHAR(50)         NOT NULL,
    status          VARCHAR(50)         NOT NULL,
    business_impact VARCHAR(255),
    expected_by     TIMESTAMP NULL,
    created_at      TIMESTAMP           NOT NULL,
    updated_at      TIMESTAMP           NOT NULL,
    resolved_at     TIMESTAMP NULL,

    raised_by       BIGINT              NOT NULL,
    assigned_to     BIGINT,
    application_id  BIGINT              NOT NULL
);