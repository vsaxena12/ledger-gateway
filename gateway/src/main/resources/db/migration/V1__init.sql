CREATE TABLE IF NOT EXISTS events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL CHECK (type IN ('CREDIT','DEBIT')),
  amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
  currency VARCHAR(8) NOT NULL,
  event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  metadata VARCHAR(2000),
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  applied_to_account BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_events_account_ts ON events(account_id, event_timestamp);
