/* 行情数据库业务服务：从持久输出补齐成交和 K线，事务仍由 MarketStore.apply 统一提交。 */
package com.itranswarp.exchange.quotation;

import com.itranswarp.exchange.securities.output.*;
import java.util.*;

public final class QuotationDbService {
  private final OutputStore outputs;
  private final MarketStore store;

  /** 复用现有共享投影仓储，不建立第二条进度更新路径。 */
  public QuotationDbService(OutputStore outputs, MarketStore store) {
    this.outputs = outputs;
    this.store = store;
  }

  /** 只把启动上界之后且本次运行产生的成交纳入广播集合，历史补齐保持静默。 */
  public Set<Long> catchUp(long startupUpper, long boot) {
    long upper = outputs.position();
    var changed = new HashSet<Long>();
    while (store.position() < upper) {
      long previous = store.position();
      var page = outputs.page(previous, upper, 100);
      if (page.isEmpty()) throw new IllegalStateException("行情来源缺失");
      for (var result : page) {
        store.apply(result);
        if (result.sequenceId() > startupUpper && result.createdAt() >= boot)
          result.trades().forEach(t -> changed.add(t.securityId()));
      }
    }
    return changed;
  }
}
