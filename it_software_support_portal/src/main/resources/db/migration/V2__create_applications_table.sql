CREATE TABLE applications
(
    id          BIGSERIAL PRIMARY KEY,
    app_name    VARCHAR(150) NOT NULL,
    module_name VARCHAR(100),
    active      BOOLEAN DEFAULT TRUE
);