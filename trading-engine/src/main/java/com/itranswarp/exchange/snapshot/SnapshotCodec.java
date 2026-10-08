/* 独立严格快照编解码：校验原始 UTF-8 文本及格式版本，不在解析错误日志中输出账户数据。 */
package com.itranswarp.exchange.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SnapshotCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    /** 工具类不允许创建实例，避免维护多个不同序列化配置。 */
    private SnapshotCodec() { }

    /** 将已校验的独立状态编码为一条完整快照行，时间不参与内容哈希。 */
    public static SnapshotRecord encode(EngineSnapshot snapshot) {
        snapshot.validateShape();
        try {
            String data = MAPPER.writeValueAsString(snapshot);
            return new SnapshotRecord(snapshot.sequenceId(), snapshot.formatVersion(), data, hash(data),
                    System.currentTimeMillis());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("快照序列化失败", e);
        }
    }

    /** 先校验文本、版本与外层序号，再返回不可变状态；损坏快照不能修改正在恢复的引擎。 */
    public static EngineSnapshot decode(SnapshotRecord record) {
        if (record == null || record.formatVersion() != EngineSnapshot.FORMAT_VERSION
                || record.sequenceId() < 0 || record.snapshotData() == null || record.checksum() == null
                || !hash(record.snapshotData()).equals(record.checksum())) {
            throw new IllegalArgumentException("快照格式版本或内容校验失败");
        }
        try {
            EngineSnapshot snapshot = MAPPER.readValue(record.snapshotData(), EngineSnapshot.class);
            if (snapshot == null || snapshot.sequenceId() != record.sequenceId()
                    || snapshot.formatVersion() != record.formatVersion()) {
                throw new IllegalArgumentException("快照外层标识与内容不一致");
            }
            snapshot.validateShape();
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("快照 JSON 无法解析");
        }
    }

    /** 对数据库原始文本计算 SHA-256；仅检测内容变化，不替代资金一致性检查或身份认证。 */
    private static String hash(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }
}
