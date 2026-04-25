CREATE TABLE applications
(
    id          BIGINT PRIMARY KEY,
    app_name    VARCHAR(255) NOT NULL,
    module_name VARCHAR(255),
    active      BOOLEAN DEFAULT TRUE
);