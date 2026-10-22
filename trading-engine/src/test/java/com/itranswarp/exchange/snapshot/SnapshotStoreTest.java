/* 快照仓储与完整初始化验证：截获 JDBC 的真实 SQL 和参数，不连接或修改 MySQL。 */
package com.itranswarp.exchange.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.itranswarp.exchange.db.DbTemplate;

class SnapshotStoreTest {
    /** 候选读取限定历史上界，按位置和追加记录 ID 降序；至多取五份，排除未来状态。 */
    @Test
    void candidateQueryIsBoundedAndOrdered() {
        StubJdbc jdbc = new StubJdbc();
        new SnapshotStore(new DbTemplate(jdbc)).findCandidates(100);
        assertTrue(jdbc.querySql.contains("WHERE sequenceId <= ? ORDER BY sequenceId DESC, id DESC LIMIT 5"));
        assertArrayEquals(new Object[] { 100L }, jdbc.queryArgs);
    }

    /** 完整快照一次插入所有元数据和原始文本，不执行替换、删除或分阶段写入。 */
    @Test
    void writesOneCompleteRecord() {
        StubJdbc jdbc = new StubJdbc();
        SnapshotRecord record = record();
        new SnapshotStore(new DbTemplate(jdbc)).save(record);
        assertTrue(jdbc.updateSql.startsWith("INSERT INTO engine_snapshots"));
        assertArrayEquals(new Object[] { record.sequenceId(), record.formatVersion(), record.snapshotData(),
                record.checksum(), record.createdAt() }, jdbc.updateArgs);
        assertEquals(1, jdbc.updates);
    }

    /** 同一有效状态重复保存不再插入，避免空闲重启无限增加等价记录。 */
    @Test
    void identicalSnapshotIsIdempotent() {
        StubJdbc jdbc = new StubJdbc();
        SnapshotRecord record = record();
        jdbc.rows.add(record);
        new SnapshotStore(new DbTemplate(jdbc)).save(record);
        assertEquals(0, jdbc.updates);
    }

    /** 损坏旧记录仍保留，但允许同一事件位置追加新的完整快照以恢复快照加速能力。 */
    @Test
    void corruptRecordIsPreservedAndReplacedByAppend() {
        StubJdbc jdbc = new StubJdbc();
        SnapshotRecord record = record();
        jdbc.rows.add(new SnapshotRecord(record.sequenceId(), record.formatVersion(), "bad", "bad", 0));
        new SnapshotStore(new DbTemplate(jdbc)).save(record);
        assertEquals(1, jdbc.updates);
        assertEquals("bad", jdbc.rows.get(0).snapshotData());
    }

    /** 同序号已有不同有效状态时不擅自覆盖，应排查配置或状态计算不确定性。 */
    @Test
    void conflictingValidSnapshotFails() {
        StubJdbc jdbc = new StubJdbc();
        SnapshotRecord record = record();
        jdbc.rows.add(SnapshotCodec.encode(new EngineSnapshot(1, 1, "Asia/Tokyo", BigDecimal.ZERO, List.of(), List.of())));
        assertThrows(IllegalStateException.class, () -> new SnapshotStore(new DbTemplate(jdbc)).save(record));
        assertEquals(0, jdbc.updates);
    }

    /** 未插入一条完整行不能报告成功，写失败由启动或周期服务各自处理。 */
    @Test
    void unsuccessfulWriteThrows() {
        StubJdbc jdbc = new StubJdbc();
        jdbc.affected = 0;
        assertThrows(IllegalStateException.class, () -> new SnapshotStore(new DbTemplate(jdbc)).save(record()));
    }

    /** 无效输入在 SQL 执行前拒绝，不能将半成品快照插入候选集合。 */
    @Test
    void invalidRecordIsNotInserted() {
        StubJdbc jdbc = new StubJdbc();
        assertThrows(IllegalArgumentException.class, () -> new SnapshotStore(new DbTemplate(jdbc))
                .save(new SnapshotRecord(1, 1, "bad", "bad", 0)));
        assertEquals(0, jdbc.updates);
    }

    /** 完整初始化一次创建快照表，验证仓储所需字段、索引和存储设置，不依赖额外脚本。 */
    @Test
    void freshSchemaIncludesCompleteSnapshotTable() throws Exception {
        Path sqlDir = Path.of(System.getProperty("basedir"), "../build/sql").normalize();
        // 去掉说明注释，只检查实际可执行的建表语句。
        String schema = Files.readString(sqlDir.resolve("schema.sql")).replaceAll("(?m)--.*$", "");
        String create = "CREATE TABLE engine_snapshots (";
        assertEquals(1, schema.split(java.util.regex.Pattern.quote(create), -1).length - 1);
        int start = schema.indexOf(create);
        // 取表级注释后的语句结束标记，不误取列注释或说明文本。
        int end = schema.indexOf("';", start) + 2;
        assertTrue(end > start, "完整初始化脚本必须包含快照表的结束标记");
        String initialDdl = schema.substring(start, end).replaceAll("\\s+", " ").trim();
        for (String required : List.of("id BIGINT NOT NULL AUTO_INCREMENT", "sequenceId BIGINT NOT NULL",
                "formatVersion INT NOT NULL", "snapshotData LONGTEXT NOT NULL",
                "checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL", "createdAt BIGINT NOT NULL",
                "PRIMARY KEY (id)", "KEY IDX_SNAPSHOT_SEQ_VERSION (sequenceId, formatVersion)",
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            assertTrue(initialDdl.contains(required), "快照建表定义缺少：" + required);
        }
        assertTrue(schema.indexOf("USE exchange;") < start);
        assertFalse(schema.matches("(?is).*INSERT\\s+INTO\\s+engine_snapshots\\b.*"));
    }

    /** 创建合法的零资产事件位置快照，不依赖外部测试数据。 */
    private SnapshotRecord record() {
        return SnapshotCodec.encode(new EngineSnapshot(1, 1, "UTC", BigDecimal.ZERO, List.of(), List.of()));
    }

    private static class StubJdbc extends JdbcTemplate {
        final List<SnapshotRecord> rows = new ArrayList<>();
        String querySql;
        Object[] queryArgs;
        String updateSql;
        Object[] updateArgs;
        int updates;
        int affected = 1;

        /** 捕获 SQL 与绑定参数，只返回预设记录，不宣称验证实际 MySQL 事务。 */
        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            querySql = sql; queryArgs = args; return (List<T>) new ArrayList<>(rows);
        }

        /** 记录一次完整写入尝试，允许模拟未成功插入。 */
        @Override
        public int update(String sql, Object... args) {
            updateSql = sql; updateArgs = args; updates++; return affected;
        }
    }
}
