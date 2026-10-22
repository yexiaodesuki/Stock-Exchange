-- 全新开发环境初始化：一次创建全部业务表与交易引擎快照表。
-- 注意：本脚本会删除并重建 exchange 数据库，仅用于明确授权的全新安装或开发环境重置。
-- 日常启动保留现有数据库，不重新执行本脚本；快照内容由引擎生成，不在 SQL 中插入。

DROP DATABASE IF EXISTS exchange;

CREATE DATABASE exchange;

USE exchange;

CREATE TABLE api_key_auths (
  apiKey VARCHAR(32) NOT NULL,
  apiSecret VARCHAR(32) NOT NULL,
  expiresAt BIGINT NOT NULL,
  userId BIGINT NOT NULL,
  PRIMARY KEY(apiKey)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE clearings (
  id BIGINT NOT NULL AUTO_INCREMENT,
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
  CONSTRAINT UNI_SEQ_ORD_CORD UNIQUE (sequenceId,orderId,counterOrderId),
  PRIMARY KEY(id)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE day_bars (
  startTime BIGINT NOT NULL,
  closePrice DECIMAL(36,18) NOT NULL,
  highPrice DECIMAL(36,18) NOT NULL,
  lowPrice DECIMAL(36,18) NOT NULL,
  openPrice DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  PRIMARY KEY(startTime)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE events (
  sequenceId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  data VARCHAR(10000) NOT NULL,
  previousId BIGINT NOT NULL,
  CONSTRAINT UNI_PREV_ID UNIQUE (previousId),
  PRIMARY KEY(sequenceId)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


-- 快照表随业务表一次创建：引擎首次启动先持久化一致状态，再开放业务。
CREATE TABLE engine_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '快照记录标识',
    sequenceId BIGINT NOT NULL COMMENT '最后完整应用的事件序号，不是数据库最大序号',
    formatVersion INT NOT NULL COMMENT '快照格式版本',
    snapshotData LONGTEXT NOT NULL COMMENT '完整状态的原始JSON文本，金额按十进制保存',
    checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始UTF-8文本的SHA-256',
    createdAt BIGINT NOT NULL COMMENT '快照记录生成时间，毫秒',
    PRIMARY KEY (id),
    KEY IDX_SNAPSHOT_SEQ_VERSION (sequenceId, formatVersion)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易引擎一致性快照；不替代事件历史或数据库备份';


CREATE TABLE hour_bars (
  startTime BIGINT NOT NULL,
  closePrice DECIMAL(36,18) NOT NULL,
  highPrice DECIMAL(36,18) NOT NULL,
  lowPrice DECIMAL(36,18) NOT NULL,
  openPrice DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  PRIMARY KEY(startTime)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE match_details (
  id BIGINT NOT NULL AUTO_INCREMENT,
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
  CONSTRAINT UNI_OID_COID UNIQUE (orderId, counterOrderId),
  INDEX IDX_OID_CT (orderId,createdAt),
  PRIMARY KEY(id)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE min_bars (
  startTime BIGINT NOT NULL,
  closePrice DECIMAL(36,18) NOT NULL,
  highPrice DECIMAL(36,18) NOT NULL,
  lowPrice DECIMAL(36,18) NOT NULL,
  openPrice DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  PRIMARY KEY(startTime)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE orders (
  id BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  direction VARCHAR(32) NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  sequenceId BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  unfilledQuantity DECIMAL(36,18) NOT NULL,
  updatedAt BIGINT NOT NULL,
  userId BIGINT NOT NULL,
  PRIMARY KEY(id)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE password_auths (
  userId BIGINT NOT NULL,
  passwd VARCHAR(100) NOT NULL,
  random VARCHAR(32) NOT NULL,
  PRIMARY KEY(userId)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE sec_bars (
  startTime BIGINT NOT NULL,
  closePrice DECIMAL(36,18) NOT NULL,
  highPrice DECIMAL(36,18) NOT NULL,
  lowPrice DECIMAL(36,18) NOT NULL,
  openPrice DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  PRIMARY KEY(startTime)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE ticks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  createdAt BIGINT NOT NULL,
  makerOrderId BIGINT NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  quantity DECIMAL(36,18) NOT NULL,
  sequenceId BIGINT NOT NULL,
  takerDirection BIT NOT NULL,
  takerOrderId BIGINT NOT NULL,
  CONSTRAINT UNI_T_M UNIQUE (takerOrderId, makerOrderId),
  INDEX IDX_CAT (createdAt),
  PRIMARY KEY(id)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE transfer_logs (
  transferId VARCHAR(32) NOT NULL,
  amount DECIMAL(36,18) NOT NULL,
  asset VARCHAR(32) NOT NULL,
  createdAt BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  type VARCHAR(32) NOT NULL,
  userId BIGINT NOT NULL,
  PRIMARY KEY(transferId)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE unique_events (
  uniqueId VARCHAR(50) NOT NULL,
  createdAt BIGINT NOT NULL,
  sequenceId BIGINT NOT NULL,
  PRIMARY KEY(uniqueId)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE user_profiles (
  userId BIGINT NOT NULL,
  createdAt BIGINT NOT NULL,
  email VARCHAR(100) NOT NULL,
  name VARCHAR(100) NOT NULL,
  updatedAt BIGINT NOT NULL,
  CONSTRAINT UNI_EMAIL UNIQUE (email),
  PRIMARY KEY(userId)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;


CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  createdAt BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  PRIMARY KEY(id)
) CHARACTER SET utf8 COLLATE utf8_general_ci AUTO_INCREMENT = 1000;
