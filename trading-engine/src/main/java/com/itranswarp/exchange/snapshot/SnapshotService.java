/* 快照协调服务：启动读取有效候选，恢复后保存首份快照，运行期间串行定期追加新快照。 */
package com.itranswarp.exchange.snapshot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.itranswarp.exchange.TradingEngineService;
import com.itranswarp.exchange.support.LoggerSupport;

@Component
public class SnapshotService extends LoggerSupport {
    private final TradingEngineService engine;
    private final SnapshotStore store;
    private long lastSavedSequence = -1;
    private ScheduledExecutorService scheduler;

    @Value("${exchange.snapshot.interval-ms:60000}")
    private long intervalMs = 60000;

    /** 复用引擎处理锁和现有数据源，不允许后台线程直接序列化可变业务对象。 */
    public SnapshotService(TradingEngineService engine, SnapshotStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** 最新快照无效时尝试较旧候选；均不可用则从零重放，不捕获数据库连接或缺表错误。 */
    public void restoreLatest(long target) {
        for (SnapshotRecord record : store.findCandidates(target)) {
            try {
                EngineSnapshot snapshot = SnapshotCodec.decode(record);
                if (snapshot.sequenceId() > target) {
                    throw new IllegalArgumentException("快照领先于历史上界");
                }
                engine.restoreSnapshot(snapshot);
                logger.info("已安装快照，序号={}", snapshot.sequenceId());
                return;
            } catch (IllegalArgumentException invalid) {
                logger.warn("跳过无效快照，序号={}，原因={}", record.sequenceId(), invalid.getMessage());
            }
        }
        logger.info("没有有效快照，使用完整事件历史恢复");
    }

    /** 启动时必须保存完整恢复结果；写入失败由恢复协调器阻止开放服务，先确保迁移已执行。 */
    public synchronized void saveRecoveredState() {
        if (intervalMs < 1) throw new IllegalArgumentException("快照检查间隔必须大于零");
        persist(engine.captureSnapshot());
    }

    /** 就绪后启动单个串行任务；初次任务等待完整间隔，不与启动快照并发写入。 */
    public synchronized void startPeriodic() {
        if (scheduler != null) throw new IllegalStateException("快照定期任务已经启动");
        // 命名守护线程，关闭时中断；故障后仍保留已提交事件及旧快照。
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "engine-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::savePeriodically, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 定期保存失败记录日志并在下轮重试；失败不推进保存位置，不清理历史也不隐藏异常。 */
    public synchronized void savePeriodically() {
        if (engine.getRecoveryState() != TradingEngineService.RecoveryState.READY) return;
        if (engine.getLastSequenceId() == lastSavedSequence) return;
        try {
            persist(engine.captureSnapshot());
        } catch (Exception e) {
            logger.error("定期快照保存失败，将在下轮重试；仍需保留完整事件历史", e);
        }
    }

    /** 捕获已在引擎锁内完成，序列化和数据库事务在锁外执行；仅提交成功才推进记录位置。 */
    private void persist(EngineSnapshot snapshot) {
        store.save(SnapshotCodec.encode(snapshot));
        lastSavedSequence = snapshot.sequenceId();
        logger.info("快照保存成功，序号={}", lastSavedSequence);
    }

    /** 停止定期任务，不以停机快照作为唯一保障；未提交记录不会成为恢复候选。 */
    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
