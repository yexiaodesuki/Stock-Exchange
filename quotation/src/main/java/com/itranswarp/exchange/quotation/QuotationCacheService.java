/* 行情缓存与发布服务：按证券完整替换缓存，保持原实时与历史广播边界。 */
package com.itranswarp.exchange.quotation;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.*;
import java.util.*;

public final class QuotationCacheService {
  private final MarketStore store;
  private final MarketCache cache;
  private final SecurityCatalog catalog;

  /** 注入同一持久投影，不将缓存误认为权威历史。 */
  public QuotationCacheService(MarketStore store, MarketCache cache, SecurityCatalog catalog) {
    this.store = store;
    this.cache = cache;
    this.catalog = catalog;
  }

  /** 缓存失败向运行协调器传播，整轮健康继续报告 DOWN 并在下轮重试。 */
  public void refresh(Set<Long> changed) { // 文档一次原子替换，包括四周期当前 K线；不会读到半个缓存重建结果。
    for (var security : catalog.all()) {
      var bars = new TreeMap<String, Object>();
      for (String period : MarketStore.PERIODS.keySet())
        bars.put(period, store.bars(security.securityId(), period, 0, Long.MAX_VALUE, 200));
      var ticks = store.ticks(security.securityId(), 100);
      long version = store.position();
      var data =
          Map.of(
              "securityId",
              Long.toString(security.securityId()),
              "sequenceId",
              Long.toString(version),
              "ticks",
              ticks,
              "bars",
              bars);
      cache.put(security.securityId(), "quotation", version, data);
      if (changed.contains(security.securityId())) {
        var lastBars = new TreeMap<String, Object>();
        for (var entry : bars.entrySet()) {
          var list = (List<?>) entry.getValue();
          if (!list.isEmpty()) lastBars.put(entry.getKey(), list.get(list.size() - 1));
        }
        cache.publish(
            "market:" + security.securityId(),
            com.itranswarp.exchange.util.JsonUtil.writeJson(
                Map.of(
                    "type",
                    "market.updated",
                    "eventId",
                    "quote:" + security.securityId() + ":" + version,
                    "securityId",
                    Long.toString(security.securityId()),
                    "sequenceId",
                    Long.toString(version),
                    "data",
                    Map.of("ticks", ticks.stream().limit(10).toList(), "bars", lastBars))));
      }
    }
  }
}
