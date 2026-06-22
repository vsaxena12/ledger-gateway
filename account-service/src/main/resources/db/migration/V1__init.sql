CREATE TABLE IF NOT EXISTS accounts (
  id VARCHAR(64) PRIMARY KEY,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL CHECK (type IN ('CREDIT','DEBIT')),
  amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
  applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_tx_account_applied ON transactions(account_id, applied_at);
