CREATE TABLE IF NOT EXISTS payments (
    id          VARCHAR(32)  PRIMARY KEY,
    amount      BIGINT       NOT NULL,
    currency    CHAR(3)      NOT NULL,
    customer    VARCHAR(64),
    status      VARCHAR(16)  NOT NULL,
    charge_id   VARCHAR(32)  NOT NULL,
    created_at  BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    event_id    VARCHAR(255) PRIMARY KEY,
    payment_id  VARCHAR(32)  NOT NULL,
    amount      BIGINT       NOT NULL
);
