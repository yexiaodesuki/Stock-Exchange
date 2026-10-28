-- 持久执行输出和可恢复投影；不删除原事件、快照或账户，不改写 V1。
CREATE TABLE engine_results (
 sequenceId BIGINT PRIMARY KEY, previousId BIGINT NOT NULL, userId BIGINT NOT NULL,
 uniqueId VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 createdAt BIGINT NOT NULL, resultData LONGTEXT NOT NULL,
 checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE engine_output_checkpoint (id INT PRIMARY KEY, sequenceId BIGINT NOT NULL, CHECK(id=1)) ENGINE=InnoDB;
INSERT INTO engine_output_checkpoint VALUES(1,0);
CREATE TABLE event_outbox (
 eventId VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 sequenceId BIGINT NOT NULL, destination VARCHAR(16) NOT NULL, routing VARCHAR(100) NOT NULL,
 payload LONGTEXT NOT NULL, createdAt BIGINT NOT NULL, sentAt BIGINT NULL,
 KEY IDX_OUTBOX_PENDING(sentAt,sequenceId,eventId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE projection_offsets (name VARCHAR(32) PRIMARY KEY, sequenceId BIGINT NOT NULL) ENGINE=InnoDB;
INSERT INTO projection_offsets VALUES('quotation',0);
CREATE TABLE api_requests (
 requestId VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 userId BIGINT NOT NULL, commandHash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 createdAt BIGINT NOT NULL
) ENGINE=InnoDB;
ALTER TABLE ticks ADD COLUMN tradeIndex INT NOT NULL DEFAULT 0,
 ADD KEY IDX_TICK_EVENT(securityId,sequenceId,tradeIndex);
