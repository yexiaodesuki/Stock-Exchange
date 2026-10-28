/* 行情先落 MySQL 再重建单证券缓存；首次补齐不广播旧成交，Redis 故障只影响缓存而非投影。 */
package com.itranswarp.exchange.quotation;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.securities.persistence.ProcessLease;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

public final class QuotationService implements AutoCloseable {
  private final OutputStore outputs;
  private final MarketStore store;
  private final MarketCache cache;
  private final SecurityCatalog catalog;
  private final QuotationDbService database;
  private final QuotationCacheService publisher;
  private final DataSource source;
  private ScheduledExecutorService worker;
  private ProcessLease lease;
  private volatile boolean ready;
  private long startupUpper;
  private final long boot = System.currentTimeMillis();

  /** 只装配已迁移的数据源，缓存可暂时不可用。 */
  public QuotationService(DataSource source, SecurityCatalog catalog, MarketCache cache) {
    this.source = source;
    this.catalog = catalog;
    this.cache = cache;
    outputs = new OutputStore(source);
    store = new MarketStore(source, catalog);
    database = new QuotationDbService(outputs, store);
    publisher = new QuotationCacheService(store, cache, catalog);
  }

  /** 先补齐启动上界并重建缓存，再开放行情健康状态。 */
  public synchronized void start() {
    lease = new ProcessLease(source, "quotation");
    startupUpper = outputs.position();
    poll();
    worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var t = new Thread(task, "securities-quotation");
              t.setDaemon(true);
              return t;
            });
    worker.scheduleWithFixedDelay(this::poll, 200, 300, TimeUnit.MILLISECONDS);
  }

  /** 持久输出才是追赶源，因此 Kafka 最后一条提示丢失也不会漏行情。 */
  public synchronized void poll() {
    try {
      lease.check();
      var changed = database.catchUp(startupUpper, boot);
      publisher.refresh(changed);
      ready = true;
    } catch (Exception failure) {
      ready = false;
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("行情或缓存未就绪，下轮重试：{}", failure.getClass().getSimpleName());
    }
  }

  /** 就绪表示投影与缓存本轮同步成功，不是线程存活。 */
  public boolean ready() {
    return ready;
  }

  /** 仅关闭本行情实例，不删除持久历史或缓存。 */
  @Override
  public synchronized void close() {
    ready = false;
    if (worker != null) worker.shutdownNow();
    if (lease != null) lease.close();
  }
}
