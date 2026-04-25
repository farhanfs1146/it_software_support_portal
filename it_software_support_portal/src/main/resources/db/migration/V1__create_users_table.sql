CREATE TABLE users
(
    id            BIGINT PRIMARY KEY,
    employee_code VARCHAR(100),
    full_name     VARCHAR(255)        NOT NULL,
    email         VARCHAR(255) UNIQUE NOT NULL,
    department    VARCHAR(255),
    designation   VARCHAR(255),
    role          VARCHAR(50)         NOT NULL,
    active        BOOLEAN DEFAULT TRUE
);