/* 行情查询服务：校验证券和投影版本，缓存失败时回源，不承担权威账户查询。 */
package com.itranswarp.exchange.service;

import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class MarketQueryService {
  private final SecurityCatalog catalog;
  private final MarketCache cache;
  private final MarketStore market;
  private final OutputStore outputs;
  private final TradingEngineApiProxyService engineProxy;
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /** 复用原数据库投影和缓存协议，不引入第二份行情状态。 */
  public MarketQueryService(
      DataSource source,
      SecurityCatalog catalog,
      MarketCache cache,
      TradingEngineApiProxyService engineProxy) {
    this.catalog = catalog;
    this.cache = cache;
    this.engineProxy = engineProxy;
    market = new MarketStore(source, catalog);
    outputs = new OutputStore(source);
  }

  /** 按证券版本校验盘口缓存，缓存失败回源引擎。 */
  public Object book(long security) throws Exception {
    catalog.require(security);
    var status = engineProxy.internal("/status");
    long expected = status.path("marketVersions").path(Long.toString(security)).asLong(-1);
    try {
      var cached = cache.get(security, "book");
      if (cached.isPresent()) {
        var value = json.readTree(cached.get());
        if (value.path("sequenceId").asLong() == expected) return wrap(security, "redis", value);
      }
    } catch (Exception unavailable) {
      /* 明确回源，不返回伪空盘口。 */
    }
    var value = engineProxy.internal("/books/" + security);
    try {
      cache.put(security, "book", value.path("sequenceId").asLong(), value);
    } catch (Exception ignored) {
      /* 查询不依赖缓存写成功。 */
    }
    return wrap(security, "engine", value);
  }

  /** 行情缓存版本一致才读取，否则从持久投影查询。 */
  public Object ticks(long security, int limit) throws Exception {
    catalog.require(security);
    MarketStore.limit(limit);
    marketReady();
    try {
      var cached = cache.get(security, "quotation");
      if (cached.isPresent() && limit <= 100) {
        var value = json.readTree(cached.get());
        if (value.path("sequenceId").asLong() == market.position()) {
          var ticks = value.path("ticks");
          return wrap(
              security,
              "redis",
              json.convertValue(ticks, List.class).stream().limit(limit).toList(),
              value.path("sequenceId").asLong());
        }
      }
    } catch (Exception unavailable) {
      /* MySQL 保留权威历史。 */
    }
    var view = market.view(() -> market.ticks(security, limit));
    return wrap(security, "mysql", view.data(), view.sequenceId());
  }

  /** 保留周期、范围、数量验证与缓存窗口判断。 */
  public Object bars(long security, String period, long from, long to, int limit) throws Exception {
    catalog.require(security);
    MarketStore.limit(limit);
    if (!MarketStore.PERIODS.containsKey(period) || from < 0 || to < from)
      throw new IllegalArgumentException("周期或范围无效");
    marketReady();
    try {
      var cached = cache.get(security, "quotation");
      if (cached.isPresent() && limit <= 200) {
        var document = json.readTree(cached.get());
        var values = document.path("bars").path(period);
        if (document.path("sequenceId").asLong() == market.position()
            && values.isArray()
            && (values.size() < 200 || from >= values.get(0).path("startTime").asLong())) {
          var selected = new ArrayList<JsonNode>();
          for (var value : values)
            if (value.path("startTime").asLong() >= from && value.path("startTime").asLong() <= to)
              selected.add(value);
          return wrap(
              security,
              "redis",
              selected.subList(Math.max(0, selected.size() - limit), selected.size()),
              document.path("sequenceId").asLong());
        }
      }
    } catch (Exception unavailable) {
      /* 缓存失败不改变行情时间范围。 */
    }
    var view = market.view(() -> market.bars(security, period, from, to, limit));
    return wrap(security, "mysql", view.data(), view.sequenceId());
  }

  /** 返回当前投影位置与数据来源。 */
  private Object wrap(long security, String source, Object value) {
    return Map.of(
        "securityId",
        Long.toString(security),
        "source",
        source,
        "projectionSequence",
        Long.toString(market.position()),
        "data",
        WireJson.tree(json.valueToTree(value)));
  }

  /** 使用读取时的投影代次，不把旧数据标成新版本。 */
  private Object wrap(long security, String source, Object value, long sequence) {
    return Map.of(
        "securityId",
        Long.toString(security),
        "source",
        source,
        "projectionSequence",
        Long.toString(sequence),
        "data",
        WireJson.tree(json.valueToTree(value)));
  }

  /** 投影落后持久输出时仍返回行情追赶中。 */
  private void marketReady() {
    if (market.position() < outputs.position())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "行情追赶中");
  }
}
