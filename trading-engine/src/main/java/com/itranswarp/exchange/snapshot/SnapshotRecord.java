/* 数据库快照行：校验值对应原始文本，而不是再次序列化后的 JSON。 */
package com.itranswarp.exchange.snapshot;

public record SnapshotRecord(long sequenceId, int formatVersion, String snapshotData, String checksum, long createdAt) { }
