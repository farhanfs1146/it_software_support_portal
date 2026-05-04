CREATE TABLE users
(
    id             BIGSERIAL PRIMARY KEY,
    employee_code  BIGINT              NOT NULL UNIQUE,
    full_name      VARCHAR(150)        NOT NULL,
    email          VARCHAR(100) UNIQUE NOT NULL,
    department_id  BIGINT,
    designation_id BIGINT,
    role           VARCHAR(50)         NOT NULL,
    active         BOOLEAN DEFAULT TRUE
);