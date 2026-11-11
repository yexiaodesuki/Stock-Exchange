/* 证券恢复和实时追赶：数据库事件为完整来源；恢复失败不开放查询，快照不依赖正常停机。 */
package com.itranswarp.exchange;

import com.itranswarp.exchange.core.SecuritiesEngine;
import com.itranswarp.exchange.core.SecuritiesSnapshot;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import com.itranswarp.exchange.securities.persistence.ProcessLease;
import com.itranswarp.exchange.snapshot.SnapshotRepository;
import java.util.concurrent.*;
import javax.sql.DataSource;

public final class TradingEngineService implements AutoCloseable {
  public enum Status {
    RECOVERING,
    READY,
    FAILED,
    STOPPED
  }

  private final EventJournal journal;
  private final SnapshotRepository snapshots;
  private final DataSource source;
  private final com.itranswarp.exchange.securities.output.OutputStore outputs;
  private long recoveryUpper;
  private final long intervalMillis;
  private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());
  private volatile Status status = Status.RECOVERING;
  private volatile SecuritiesEngine engine;
  private ProcessLease lease;
  private ScheduledExecutorService worker;
  private long savedSequence = -1, lastSnapshotTime;
  private volatile long target;

  /** 仅装配依赖；不在构造器开放消费者或读取半初始化状态。 */
  public TradingEngineService(DataSource source, SecurityCatalog catalog, long intervalMillis) {
    if (intervalMillis < 1) throw new IllegalArgumentException("快照周期必须为正数");
    this.source = source;
    this.intervalMillis = intervalMillis;
    this.journal = new EventJournal(source, catalog);
    this.snapshots = new SnapshotRepository(source, catalog);
    this.outputs = new com.itranswarp.exchange.securities.output.OutputStore(source);
  }

  /** 取得实例锁、恢复固定上界、保存启动快照后才标为 READY；失败对外保持不可用。 */
  public synchronized void start() {
    if (worker != null || status != Status.RECOVERING)
      throw new IllegalStateException("证券引擎不能重复启动");
    try {
      lease = new ProcessLease(source, "engine");
      target = journal.latest();
      recoveryUpper = target;
      if (outputs.position() > target) throw new IllegalStateException("持久输出领先于事件历史");
      engine = snapshots.restore(Math.min(target, outputs.position()));
      catchUp(target, SecuritiesEngine.Mode.REPLAY);
      save();
      // 恢复期间提交的新事件也从持久日志追赶；不依赖 Kafka 通知刚好到达。
      target = journal.latest();
      catchUp(target, SecuritiesEngine.Mode.REPLAY);
      if (engine.sequence() != savedSequence) save();
      worker =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                var thread = new Thread(task, "securities-engine-tail");
                thread.setDaemon(true);
                return thread;
              });
      status = Status.READY;
      worker.scheduleWithFixedDelay(this::poll, 200, 200, TimeUnit.MILLISECONDS);
    } catch (RuntimeException | Error failure) {
      status = Status.FAILED;
      if (lease != null) lease.close();
      throw failure;
    }
  }

  /** 数据库定时追赶也是 Kafka 丢通知时的兜底；只由一个执行通道推进状态。 */
  public synchronized void poll() {
    if (status != Status.READY) return;
    try {
      lease.check();
      target = journal.latest();
      catchUp(target, SecuritiesEngine.Mode.LIVE);
    } catch (RuntimeException | Error failure) {
      status = Status.FAILED;
      logger.error("证券事件追赶失败，禁止继续服务", failure);
      return;
    }
    if (System.currentTimeMillis() - lastSnapshotTime >= intervalMillis
        && engine.sequence() != savedSequence) {
      try {
        save();
      } catch (RuntimeException failure) {
        logger.error("周期快照失败，保留历史并下轮重试", failure);
      }
    }
  }

  /** 只在完整事件边界执行；缺口、停滞、超出固定上界都中止恢复。 */
  private void catchUp(long upper, SecuritiesEngine.Mode mode) {
    while (engine.sequence() < upper) {
      lease.check();
      long previous = engine.sequence();
      var page = journal.page(previous, upper, 1000);
      if (page.isEmpty()) throw new IllegalStateException("已提交事件历史缺失");
      for (var event : page) {
        var prepared = engine.prepare(event);
        outputs.save(
            event,
            prepared.output(),
            mode == SecuritiesEngine.Mode.REPLAY || event.sequenceId() <= recoveryUpper);
        engine.install(prepared);
      }
      if (engine.sequence() <= previous || engine.sequence() > upper)
        throw new IllegalStateException("事件分页未正确推进");
    }
  }

  /** 捕获完整状态后编码/写库；核心执行锁不跨 JDBC I/O，成功后才记录保存位置。 */
  public synchronized void save() {
    if (status == Status.FAILED || status == Status.STOPPED)
      throw new IllegalStateException("当前状态禁止保存快照");
    if (engine == null || lease == null) throw new IllegalStateException("证券引擎尚未初始化");
    lease.check();
    var value = engine.snapshot();
    snapshots.save(value);
    savedSequence = value.sequenceId();
    lastSnapshotTime = System.currentTimeMillis();
  }

  /** 只有 READY 才返回状态副本；恢复中的查询不能表现为正常空资产。 */
  public SecuritiesSnapshot state() {
    requireReady();
    var value = engine.snapshot();
    requireReady();
    return value;
  }

  /** 返回指定用户只读账户，内部负债账户不能作为普通用户查询。 */
  public SecuritiesEngine.Account account(long user) {
    requireReady();
    if (user <= 1) throw new IllegalArgumentException("普通用户标识无效");
    var value = engine.account(user);
    requireReady();
    return value;
  }

  /** 就绪是显式生命周期，不等同于 JVM 已启动。 */
  public Status status() {
    return status;
  }

  /** 公开盘口只含价量，历史/缓存不可用时可以回源本方法。 */
  public com.itranswarp.exchange.securities.output.ExecutionEnvelope.Book book(long security) {
    requireReady();
    var value = engine.book(security);
    requireReady();
    return value;
  }

  /** 监控恢复与追赶目标，不把落库上界解释为已执行位置。 */
  public long target() {
    return target;
  }

  /** 查询已应用进度，不泄露恢复中的账户对象。 */
  public long applied() {
    var current = engine;
    return current == null ? 0 : current.sequence();
  }

  /** 未就绪统一拒绝，HTTP 层映射成 503。 */
  private void requireReady() {
    if (status != Status.READY) throw new IllegalStateException("证券引擎未就绪: " + status);
  }

  /** 停止本实例线程并释放锁；不依赖停机保存，也不停止容器或其他应用。 */
  @Override
  public synchronized void close() {
    status = Status.STOPPED;
    if (worker != null) worker.shutdownNow();
    if (lease != null) lease.close();
  }
}
