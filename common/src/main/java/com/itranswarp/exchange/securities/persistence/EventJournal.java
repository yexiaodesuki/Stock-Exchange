/* 证券事件日志：事务内串行定序、内容幂等和已提交事件读取，不依赖 Kafka offset 恢复资金。 */
package com.itranswarp.exchange.securities.persistence;

import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.StorageCompatibility;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.util.JsonUtil;

public final class EventJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SecurityCatalog catalog;

    /** 只接受已迁移的证券数据源，事务模板保证事件与幂等键共同提交或回滚。 */
    public EventJournal(DataSource source, SecurityCatalog catalog) {
        StorageCompatibility.requireSecurities(source);
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.catalog = catalog;
    }

    /** 锁住固定元数据行后分配序号；重试相同内容返回原事件，不同内容拒绝。 */
    public SequencedEvent append(Submission submission) {
        Command command = canonical(submission.command());
        if (command instanceof PlaceOrder place) place.validate(catalog);
        if (command instanceof CreditPosition credit) catalog.require(credit.securityId());
        return transaction.execute(status -> {
            // 固定行始终存在，空事件库也有可锁对象，避免两个定序调用同时分配首个序号。
            String family = jdbc.queryForObject("SELECT family FROM storage_metadata WHERE id=1 FOR UPDATE", String.class);
            if (!"securities-v1".equals(family)) throw new IllegalStateException("存储家族已改变");
            var prior = jdbc.query("SELECT e.* FROM unique_events u JOIN events e ON e.sequenceId=u.sequenceId WHERE u.uniqueId=?",
                    (rs, row) -> decode(rs), submission.uniqueId());
            if (!prior.isEmpty()) {
                var old = prior.get(0);
                if (old.userId() != submission.userId() || !JsonUtil.writeJson(canonical(old.command())).equals(JsonUtil.writeJson(command)))
                    throw new IllegalArgumentException("幂等标识已用于不同的用户或业务内容");
                return old;
            }
            long previous = latest();
            long next = Math.incrementExact(previous);
            long time = System.currentTimeMillis();
            if (previous > 0) time = Math.max(time, jdbc.queryForObject("SELECT createdAt FROM events WHERE sequenceId=?", Long.class, previous));
            var event = new SequencedEvent(2, 1, next, previous, submission.uniqueId(), submission.userId(), time, command);
            jdbc.update("INSERT INTO events(sequenceId,previousId,createdAt,protocolVersion,catalogVersion,data) VALUES (?,?,?,?,?,?)",
                    next, previous, time, 2, 1, JsonUtil.writeJson(event));
            jdbc.update("INSERT INTO unique_events(uniqueId,createdAt,sequenceId) VALUES (?,?,?)", submission.uniqueId(), time, next);
            return event;
        });
    }

    /** 返回已提交日志上界；空库为零，事件序号不从 Kafka 消费位置推断。 */
    public long latest() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(sequenceId),0) FROM events", Long.class);
        return value == null ? 0 : value;
    }

    /** 读取固定上界内的有序分页；SQL 列与 JSON 标识必须完全相符。 */
    public List<SequencedEvent> page(long after, long upper, int limit) {
        if (after < 0 || upper < after || limit < 1 || limit > 10000) throw new IllegalArgumentException("事件分页范围无效");
        return jdbc.query("SELECT * FROM events WHERE sequenceId>? AND sequenceId<=? ORDER BY sequenceId LIMIT ?",
                (rs, row) -> decode(rs), after, upper, limit);
    }

    /** 严格核对持久字段，禁止损坏记录悄悄推进执行位置。 */
    private SequencedEvent decode(java.sql.ResultSet rs) throws java.sql.SQLException {
        var event = JsonUtil.readJson(rs.getString("data"), SequencedEvent.class);
        if (event.sequenceId() != rs.getLong("sequenceId") || event.previousId() != rs.getLong("previousId")
                || event.createdAt() != rs.getLong("createdAt") || event.protocolVersion() != rs.getInt("protocolVersion")
                || event.catalogVersion() != rs.getInt("catalogVersion")) throw new IllegalStateException("事件 JSON 与数据库列不一致");
        return event;
    }

    /** 统一十进制表示，使 10.0 与 10.00 的同一重试不会被误认为不同业务。 */
    public static Command canonical(Command command) {
        if (command instanceof PlaceOrder p) return new PlaceOrder(p.securityId(), p.direction(), number(p.price()), number(p.quantity()));
        if (command instanceof CreditCash c) return new CreditCash(c.currency(), number(c.amount()));
        if (command instanceof CreditPosition p) return new CreditPosition(p.securityId(), number(p.quantity()));
        return command;
    }

    /** 零值固定为 ZERO，其余去掉无意义尾零；不改变金额数值。 */
    private static BigDecimal number(BigDecimal value) { return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros(); }
}
