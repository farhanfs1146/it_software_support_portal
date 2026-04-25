CREATE TABLE ticket_attachments
(
    id          BIGINT PRIMARY KEY,
    file_name   VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_type   VARCHAR(100),
    uploaded_at TIMESTAMP    NOT NULL,
    ticket_id   BIGINT       NOT NULL
);