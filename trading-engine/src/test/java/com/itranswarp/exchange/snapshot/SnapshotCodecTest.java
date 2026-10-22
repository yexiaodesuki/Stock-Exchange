/* 严格快照格式测试：版本、外层标识、文本损坏和账户内容校验，禁止静默接受半成品。 */
package com.itranswarp.exchange.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.itranswarp.exchange.enums.AssetEnum;

class SnapshotCodecTest {
    /** 无法识别的时区属于坏快照，应返回可回退的格式错误而非意外终止候选处理。 */
    @Test
    void malformedZoneIsRejectedAsInvalidSnapshot() {
        EngineSnapshot snapshot = new EngineSnapshot(1, 1, "not-a-zone", BigDecimal.ZERO, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encode(snapshot));
    }

    /** 内容变动但未更新哈希，或外层版本、序号不一致均拒绝。 */
    @Test
    void metadataAndChecksumAreVerified() {
        SnapshotRecord valid = SnapshotCodec.encode(empty());
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(new SnapshotRecord(2, 1,
                valid.snapshotData(), valid.checksum(), 0)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(new SnapshotRecord(1, 99,
                valid.snapshotData(), valid.checksum(), 0)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(new SnapshotRecord(1, 1,
                valid.snapshotData() + " ", valid.checksum(), 0)));
    }

    /** 即使哈希正确，非法 JSON、缺字段、尾随内容和未支持字段也不能成为有效快照。 */
    @Test
    void malformedJsonWithValidHashIsRejected() throws Exception {
        for (String data : List.of("{}", "null", "not-json",
                SnapshotCodec.encode(empty()).snapshotData() + " {}",
                SnapshotCodec.encode(empty()).snapshotData().replace("\"formatVersion\":1", "\"extra\":1,\"formatVersion\":1"))) {
            assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(raw(data)));
        }
    }

    /** 快照不能漏掉内部负债账户，也不能含有负的交易账户余额或重复资产。 */
    @Test
    void invalidBalancesAndDuplicateAssetsAreRejected() {
        var positive = new EngineSnapshot.AssetState(1001, AssetEnum.USD, BigDecimal.ONE, BigDecimal.ZERO);
        var negative = new EngineSnapshot.AssetState(1001, AssetEnum.USD, BigDecimal.ONE.negate(), BigDecimal.ZERO);
        for (var assets : List.of(List.of(positive), List.of(negative), List.of(positive, positive))) {
            EngineSnapshot invalid = new EngineSnapshot(1, 1, "UTC", BigDecimal.ZERO, assets, List.of());
            assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encode(invalid));
        }
    }

    /** 列表复制不受原集合后续清空影响，不能将调用者集合当成永久快照。 */
    @Test
    void inputCollectionsAreDefensivelyCopied() {
        var balances = new java.util.ArrayList<EngineSnapshot.AssetState>();
        balances.add(new EngineSnapshot.AssetState(1, AssetEnum.USD, BigDecimal.ONE.negate(), BigDecimal.ZERO));
        balances.add(new EngineSnapshot.AssetState(1001, AssetEnum.USD, BigDecimal.ONE, BigDecimal.ZERO));
        EngineSnapshot snapshot = new EngineSnapshot(1, 1, "UTC", BigDecimal.ZERO, balances, List.of());
        balances.clear();
        assertEquals(2, snapshot.assets().size());
        snapshot.validateShape();
    }

    /** 为解析安全性用例计算正确哈希，证明失败原因不是单纯 checksum 不一致。 */
    private SnapshotRecord raw(String data) throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(StandardCharsets.UTF_8)));
        return new SnapshotRecord(1, 1, data, checksum, 0);
    }

    /** 创建可用于严格序列化测试的合法空资产快照。 */
    private EngineSnapshot empty() { return new EngineSnapshot(1, 1, "UTC", BigDecimal.ZERO, List.of(), List.of()); }
}
