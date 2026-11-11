-- 模拟 USD 入金：申请、每日预占额度与待发送命令同事务落库；余额仍由交易引擎记账。
CREATE TABLE deposit_requests (
  depositId VARCHAR(36) NOT NULL PRIMARY KEY,
  userId BIGINT NOT NULL,
  keyHash CHAR(64) NOT NULL,
  amount DECIMAL(36,2) NOT NULL,
  quotaDate DATE NOT NULL,
  status VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  sequenceId BIGINT NULL,
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  UNIQUE KEY uk_deposit_user_key (userId,keyHash),
  KEY ix_deposit_user_time (userId,createdAt,depositId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deposit_daily_limits (
  userId BIGINT NOT NULL,
  quotaDate DATE NOT NULL,
  reservedAmount DECIMAL(36,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (userId,quotaDate)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deposit_outbox (
  depositId VARCHAR(36) NOT NULL PRIMARY KEY,
  attempts BIGINT NOT NULL DEFAULT 0,
  nextAttemptAt BIGINT NOT NULL,
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  KEY ix_deposit_outbox_due (completed,nextAttemptAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
