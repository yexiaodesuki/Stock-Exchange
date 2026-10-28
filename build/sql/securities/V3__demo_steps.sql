-- 演示步骤独立登记；不修改历史迁移、不直接插入用户资产或行情。
CREATE TABLE demo_steps (
  stepId VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  runId VARCHAR(100) NOT NULL,
  userId BIGINT NOT NULL,
  securityId BIGINT NULL,
  commandHash CHAR(64) NOT NULL,
  submissionData LONGTEXT NOT NULL,
  sequenceId BIGINT NULL,
  status VARCHAR(16) NOT NULL,
  updatedAt BIGINT NOT NULL,
  FOREIGN KEY (runId) REFERENCES bootstrap_runs(id),
  CHECK (status IN ('PENDING','COMPLETE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
