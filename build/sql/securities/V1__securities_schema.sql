-- 全新证券环境的唯一 V1 基线；只建表和证券目录，不删库、不造资金、不导入旧 BTC 数据。
-- 已执行的版本不可改写；后续结构变化增加 V2 等迁移，由 Flyway 校验历史。

CREATE TABLE securities (
  securityId BIGINT PRIMARY KEY,
  symbol VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  quoteCurrency VARCHAR(3) NOT NULL,
  priceTick DECIMAL(18,2) NOT NULL,
  quantityStep DECIMAL(18,0) NOT NULL,
  status VARCHAR(16) NOT NULL,
  definitionVersion INT NOT NULL,
  CHECK (securityId > 0 AND quoteCurrency = 'USD' AND priceTick = 0.01 AND quantityStep = 1),
  CHECK (status IN ('ACTIVE','SUSPENDED') AND definitionVersion = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO securities VALUES
 (1001,'STAR','星河科技','USD',0.01,1,'ACTIVE',1),
 (1002,'GREEN','青山能源','USD',0.01,1,'ACTIVE',1),
 (1003,'CARE','安康医药','USD',0.01,1,'ACTIVE',1),
 (1004,'DAILY','优选消费','USD',0.01,1,'ACTIVE',1),
 (1005,'SAIL','远航工业','USD',0.01,1,'ACTIVE',1);

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  createdAt BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 AUTO_INCREMENT=1000;

CREATE TABLE user_profiles (
  userId BIGINT PRIMARY KEY,
  createdAt BIGINT NOT NULL,
  email VARCHAR(100) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  updatedAt BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE password_auths (
  userId BIGINT PRIMARY KEY,
  passwd VARCHAR(100) NOT NULL,
  random VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_key_auths (
  apiKey VARCHAR(32) PRIMARY KEY,
  apiSecret VARCHAR(32) NOT NULL,
  expiresAt BIGINT NOT NULL,
  userId BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 权威状态仍由持久事件和一致性快照重建；不增加与之竞争的余额更新表。
CREATE TABLE events (
  sequenceId BIGINT PRIMARY KEY,
  previousId BIGINT NOT NULL UNIQUE,
  createdAt BIGINT NOT NULL,
  protocolVersion INT NOT NULL,
  catalogVersion INT NOT NULL,
  data LONGTEXT NOT NULL,
  CHECK (sequenceId > previousId AND previousId >= 0),
  CHECK (protocolVersion = 2 AND catalogVersion = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE unique_events (
  uniqueId VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  createdAt BIGINT NOT NULL,
  sequenceId BIGINT NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE engine_snapshots (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  sequenceId BIGINT NOT NULL,
  formatVersion INT NOT NULL,
  catalogVersion INT NOT NULL,
  snapshotData LONGTEXT NOT NULL,
  checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  createdAt BIGINT NOT NULL,
  KEY IDX_SNAPSHOT_SEQ_VERSION(sequenceId,formatVersion),
  CHECK (sequenceId >= 0 AND formatVersion = 2 AND catalogVersion = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='共享现金、分证券持仓和订单的完整事件边界快照，第二阶段实现编码';

CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  securityId BIGINT NOT NULL,
  userId BIGINT NOT NULL,
  sequenceId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  direction VARCHAR(32) NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  unfilledQuantity DECIMAL(36,18) NOT NULL,
  status VARCHAR(32) NOT NULL,
  KEY IDX_ORDER_USER_SECURITY_TIME(userId,securityId,createdAt),
  FOREIGN KEY (securityId) REFERENCES securities(securityId),
  CHECK (direction IN ('BUY','SELL') AND price > 0 AND MOD(price,0.01) = 0),
  CHECK (quantity > 0 AND MOD(quantity,1) = 0 AND unfilledQuantity >= 0 AND unfilledQuantity <= quantity AND MOD(unfilledQuantity,1) = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE match_details (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  securityId BIGINT NOT NULL,
  counterOrderId BIGINT NOT NULL,
  counterUserId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  direction VARCHAR(32) NOT NULL,
  orderId BIGINT NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  sequenceId BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  userId BIGINT NOT NULL,
  UNIQUE KEY UNI_MATCH(securityId,orderId,counterOrderId),
  KEY IDX_MATCH_USER_SECURITY_TIME(userId,securityId,createdAt),
  FOREIGN KEY (securityId) REFERENCES securities(securityId),
  CHECK (price > 0 AND MOD(price,0.01)=0 AND quantity > 0 AND MOD(quantity,1)=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE clearings (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  securityId BIGINT NOT NULL,
  counterOrderId BIGINT NOT NULL,
  counterUserId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  direction VARCHAR(32) NOT NULL,
  matchPrice DECIMAL(36,18) NOT NULL,
  matchQuantity DECIMAL(36,18) NOT NULL,
  orderId BIGINT NOT NULL,
  orderStatusAfterClearing VARCHAR(32) NOT NULL,
  orderUnfilledQuantityAfterClearing DECIMAL(36,18) NOT NULL,
  sequenceId BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  userId BIGINT NOT NULL,
  UNIQUE KEY UNI_CLEARING(sequenceId,securityId,orderId,counterOrderId),
  KEY IDX_CLEARING_USER_SECURITY_TIME(userId,securityId,createdAt),
  FOREIGN KEY (securityId) REFERENCES securities(securityId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE transfer_logs (
  transferId VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  amount DECIMAL(36,18) NOT NULL,
  assetKind VARCHAR(16) NOT NULL,
  currency VARCHAR(3) NULL,
  securityId BIGINT NULL,
  sequenceId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  type VARCHAR(32) NOT NULL,
  userId BIGINT NOT NULL,
  FOREIGN KEY (securityId) REFERENCES securities(securityId),
  CHECK (amount > 0),
  CHECK ((assetKind='CASH' AND currency IS NOT NULL AND currency='USD' AND securityId IS NULL AND MOD(amount,0.01)=0)
      OR (assetKind='POSITION' AND currency IS NULL AND securityId IS NOT NULL AND MOD(amount,1)=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticks (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  securityId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  makerOrderId BIGINT NOT NULL,
  takerOrderId BIGINT NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  sequenceId BIGINT NOT NULL,
  takerDirection BIT NOT NULL,
  UNIQUE KEY UNI_TICK(securityId,takerOrderId,makerOrderId),
  KEY IDX_TICK_SECURITY_TIME(securityId,createdAt,id),
  FOREIGN KEY (securityId) REFERENCES securities(securityId),
  CHECK (price > 0 AND MOD(price,0.01)=0 AND quantity > 0 AND MOD(quantity,1)=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ORM 只支持单个 @Id；每种周期使用代理主键和证券/时间唯一键。
CREATE TABLE sec_bars (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  securityId BIGINT NOT NULL,
  startTime BIGINT NOT NULL,
  openPrice DECIMAL(36,18) NOT NULL,
  highPrice DECIMAL(36,18) NOT NULL,
  lowPrice DECIMAL(36,18) NOT NULL,
  closePrice DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  UNIQUE KEY UNI_BAR(securityId,startTime),
  FOREIGN KEY (securityId) REFERENCES securities(securityId),
  CHECK (quantity >= 0 AND MOD(quantity,1)=0),
  CHECK (lowPrice > 0 AND lowPrice <= openPrice AND lowPrice <= closePrice AND highPrice >= openPrice AND highPrice >= closePrice)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE min_bars LIKE sec_bars;
CREATE TABLE hour_bars LIKE sec_bars;
CREATE TABLE day_bars LIKE sec_bars;
-- MySQL LIKE 不复制外键，显式补齐，防止不存在的证券进入其他周期。
ALTER TABLE min_bars ADD FOREIGN KEY (securityId) REFERENCES securities(securityId);
ALTER TABLE hour_bars ADD FOREIGN KEY (securityId) REFERENCES securities(securityId);
ALTER TABLE day_bars ADD FOREIGN KEY (securityId) REFERENCES securities(securityId);

CREATE TABLE bootstrap_runs (
  id VARCHAR(100) PRIMARY KEY,
  version INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  updatedAt BIGINT NOT NULL,
  CHECK (status IN ('RUNNING','COMPLETE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后续演示初始化进度，资金幂等仍由事件唯一标识保障';

-- 最后写入家族标记；脚本仍以 Flyway 成功退出和历史校验为完整迁移标准。
CREATE TABLE storage_metadata (
  id INT PRIMARY KEY,
  family VARCHAR(32) NOT NULL,
  protocolVersion INT NOT NULL,
  catalogVersion INT NOT NULL,
  CHECK (id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO storage_metadata VALUES (1,'securities-v1',2,1);
