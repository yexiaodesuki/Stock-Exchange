/* 历史查询服务：校验订单归属并只读本人指定证券的最终订单和成交明细。 */
package com.itranswarp.exchange.service;

import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.MarketStore;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

public final class HistoryService {
  private final JdbcTemplate jdbc;
  private final SecurityCatalog catalog;
  private final TradingEngineApiProxyService engineProxy;

  /** 历史投影只读，账户身份由调用层认证后传入。 */
  public HistoryService(
      DataSource source, SecurityCatalog catalog, TradingEngineApiProxyService engineProxy) {
    jdbc = new JdbcTemplate(source);
    this.catalog = catalog;
    this.engineProxy = engineProxy;
  }

  /** 只返回本人与指定证券的最终订单，查询前保留引擎就绪检查。 */
  public Object history(long user, long securityId, int limit) {
    catalog.require(securityId);
    MarketStore.limit(limit);
    engineProxy.internal("/status");
    return jdbc.queryForList(
        "SELECT * FROM orders WHERE userId=? AND securityId=? AND status IN"
            + " ('FULLY_FILLED','FULLY_CANCELLED','PARTIAL_CANCELLED') ORDER BY updatedAt DESC,id"
            + " DESC LIMIT ?",
        user,
        securityId,
        limit);
  }

  /** 验证订单归属后返回成交明细，不泄露对手身份。 */
  public Object matches(long user, long order, long securityId) {
    catalog.require(securityId);
    engineProxy.internal("/status");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id=? AND userId=? AND securityId=?",
            Integer.class,
            order,
            user,
            securityId)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return jdbc.queryForList(
        "SELECT securityId,orderId,price,quantity,direction,type,createdAt,sequenceId FROM"
            + " match_details WHERE userId=? AND orderId=? AND securityId=? ORDER BY sequenceId,id",
        user,
        order,
        securityId);
  }
}
