/* MySQL 快照仓储：只追加完整快照，同一事件位置重复保存必须内容一致，不覆盖旧记录。 */
package com.itranswarp.exchange.snapshot;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.itranswarp.exchange.db.DbTemplate;

@Component
public class SnapshotStore {
    private final JdbcTemplate jdbc;

    /** 复用当前交易系统的数据源，不新建数据库或连接独立快照实例。 */
    public SnapshotStore(DbTemplate db) {
        this.jdbc = db.getJdbcTemplate();
    }

    /** 按最新位置优先读取至多五份候选；不选择领先于已提交事件上界的快照。 */
    @Transactional(readOnly = true)
    public List<SnapshotRecord> findCandidates(long upperBound) {
        return jdbc.query("SELECT sequenceId, formatVersion, snapshotData, checksum, createdAt "
                + "FROM engine_snapshots WHERE sequenceId <= ? ORDER BY sequenceId DESC, id DESC LIMIT 5",
                (rs, index) -> new SnapshotRecord(rs.getLong("sequenceId"), rs.getInt("formatVersion"),
                        rs.getString("snapshotData"), rs.getString("checksum"), rs.getLong("createdAt")), upperBound);
    }

    /** 单条快照在事务提交后才可见；缺表或写失败向调用方抛出，不假装持久化成功。 */
    @Transactional
    public void save(SnapshotRecord record) {
        SnapshotCodec.decode(record);
        List<SnapshotRecord> existing = jdbc.query("SELECT sequenceId, formatVersion, snapshotData, checksum, createdAt "
                + "FROM engine_snapshots WHERE sequenceId = ? AND formatVersion = ? ORDER BY id DESC",
                (rs, index) -> new SnapshotRecord(rs.getLong("sequenceId"), rs.getInt("formatVersion"),
                        rs.getString("snapshotData"), rs.getString("checksum"), rs.getLong("createdAt")),
                record.sequenceId(), record.formatVersion());
        for (SnapshotRecord old : existing) {
            try {
                SnapshotCodec.decode(old);
            } catch (IllegalArgumentException invalid) {
                // 损坏记录保留供排查；允许追加同序号的新完整记录，不修改原始证据。
                continue;
            }
            if (!old.checksum().equals(record.checksum())) {
                throw new IllegalStateException("同一快照位置已存在不同有效内容，拒绝覆盖");
            }
            return;
        }
        int inserted = jdbc.update("INSERT INTO engine_snapshots "
                + "(sequenceId, formatVersion, snapshotData, checksum, createdAt) VALUES (?, ?, ?, ?, ?)",
                record.sequenceId(), record.formatVersion(), record.snapshotData(), record.checksum(), record.createdAt());
        if (inserted != 1) {
            throw new IllegalStateException("完整快照未成功写入");
        }
    }
}
