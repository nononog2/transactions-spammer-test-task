CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY,
    request_id UUID NULL,
    status VARCHAR(32) NOT NULL,
    account_from VARCHAR(64) NOT NULL,
    account_to VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    processing_started_at TIMESTAMPTZ NULL,
    finalized_at TIMESTAMPTZ NULL,
    failure_reason TEXT NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_transactions_status CHECK (status IN ('IN_PROGRESS', 'SUCCEED', 'FAILED')),
    CONSTRAINT chk_transactions_finalized_consistency CHECK (
        (status = 'IN_PROGRESS' AND finalized_at IS NULL)
        OR (status IN ('SUCCEED', 'FAILED') AND finalized_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_request_id
    ON transactions (request_id)
    WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_status_created
    ON transactions (status, created_at);

CREATE INDEX IF NOT EXISTS idx_transactions_in_progress_created
    ON transactions (created_at)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX IF NOT EXISTS idx_transactions_deadline
    ON transactions (deadline_at);

-- Transition helper index for status updates by id + current status.
CREATE INDEX IF NOT EXISTS idx_transactions_id_status
    ON transactions (id, status);
