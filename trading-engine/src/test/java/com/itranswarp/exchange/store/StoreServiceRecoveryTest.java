/* 事件仓储恢复测试：截获真实 ORM 生成的 SQL 和参数，验证分页边界与历史内容校验。 */
package com.itranswarp.exchange.store;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import com.itranswarp.exchange.db.DbTemplate;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.messaging.MessageTypes;
import com.itranswarp.exchange.model.trade.EventEntity;

class StoreServiceRecoveryTest {
    /** 空历史上界为零，查询按降序且只读取一个事件标识。 */
    @Test
    void capturesLatestCommittedSequence() {
        StubJdbc jdbc = new StubJdbc();
        StoreService store = store(jdbc);
        assertEquals(0, store.getLatestEventSequenceId());
        assertTrue(jdbc.sql.contains("ORDER BY sequenceId DESC"));
        assertArrayEquals(new Object[] { 0, 1 }, jdbc.args);
        jdbc.rows = List.of(row(store.messageTypes));
        assertEquals(12, store.getLatestEventSequenceId());
    }

    /** 恢复分页包含游标、固定上界、升序和页容量，使用真实消息反序列化。 */
    @Test
    void boundedPageUsesExpectedSqlAndParameters() {
        StubJdbc jdbc = new StubJdbc();
        StoreService store = store(jdbc);
        jdbc.rows = List.of(row(store.messageTypes));
        var page = store.loadEventsFromDb(11, 20, 1000);
        assertTrue(jdbc.sql.contains("WHERE sequenceId > ? AND sequenceId <= ? ORDER BY sequenceId"));
        assertArrayEquals(new Object[] { 11L, 20L, 0, 1000 }, jdbc.args);
        assertEquals(12, page.get(0).sequenceId);
        assertInstanceOf(TransferEvent.class, page.get(0));
    }

    /** 列与消息中的序号、前序号或时间不同都拒绝，不掩盖历史损坏。 */
    @Test
    void rejectsMismatchedMetadata() {
        StubJdbc jdbc = new StubJdbc();
        StoreService store = store(jdbc);
        for (int field = 0; field < 3; field++) {
            EventEntity row = row(store.messageTypes);
            if (field == 0) row.sequenceId++;
            if (field == 1) row.previousId++;
            if (field == 2) row.createdAt++;
            jdbc.rows = List.of(row);
            assertThrows(IllegalStateException.class, () -> store.loadEventsFromDb(0, 20, 1000));
        }
    }

    /** 无法解析的消息直接失败，不能以空事件或默认转账替代。 */
    @Test
    void rejectsMalformedEventData() {
        StubJdbc jdbc = new StubJdbc();
        StoreService store = store(jdbc);
        EventEntity row = row(store.messageTypes);
        row.data = "损坏消息";
        jdbc.rows = List.of(row);
        assertThrows(RuntimeException.class, () -> store.loadEventsFromDb(0, 20, 1000));
    }

    /** 非法页容量和逆序边界必须在执行 SQL 前拒绝。 */
    @Test
    void rejectsInvalidPaginationArguments() {
        StoreService store = store(new StubJdbc());
        assertThrows(IllegalArgumentException.class, () -> store.loadEventsFromDb(-1, 20, 1000));
        assertThrows(IllegalArgumentException.class, () -> store.loadEventsFromDb(21, 20, 1000));
        assertThrows(IllegalArgumentException.class, () -> store.loadEventsFromDb(0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> store.loadEventsFromDb(0, 20, 100001));
    }

    /** 装配真实 ORM 和消息扫描器，只有 JDBC 执行由替身代替，不连接数据库。 */
    private StoreService store(StubJdbc jdbc) {
        StoreService store = new StoreService();
        store.dbTemplate = new DbTemplate(jdbc);
        store.messageTypes = new MessageTypes();
        store.messageTypes.init();
        return store;
    }

    /** 创建与消息标识一致的持久事件行，用于校验和 SQL 参数测试。 */
    private EventEntity row(MessageTypes types) {
        TransferEvent e = new TransferEvent();
        e.sequenceId = 12;
        e.previousId = 11;
        e.createdAt = 1000;
        e.fromUserId = 1L;
        e.toUserId = 1001L;
        e.asset = AssetEnum.USD;
        e.amount = BigDecimal.ONE;
        EventEntity row = new EventEntity();
        row.sequenceId = e.sequenceId;
        row.previousId = e.previousId;
        row.createdAt = e.createdAt;
        row.data = types.serialize(e);
        return row;
    }

    private static class StubJdbc extends JdbcTemplate {
        String sql;
        Object[] args;
        List<EventEntity> rows = List.of();

        /** 捕获 SQL 与参数并返回预设实体，不验证真实 MySQL 事务或驱动。 */
        @Override
        @SuppressWarnings("unchecked")
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
            this.sql = sql;
            this.args = args;
            return (T) new ArrayList<>(rows);
        }
    }
}
