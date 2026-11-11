/* 模拟入金策略：所有金额使用精确十进制，显式开关与日额度由配置中心提供。 */
package com.itranswarp.exchange.service;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record DepositPolicy(boolean enabled, BigDecimal minimum, BigDecimal maximum, BigDecimal daily) {
  /** 配置错误阻止启动，不能用无界额度或隐式舍入继续运行。 */
  public DepositPolicy {
    if (!valid(minimum) || !valid(maximum) || !valid(daily)
        || minimum.compareTo(maximum)>0 || maximum.compareTo(daily)>0)
      throw new IllegalArgumentException("模拟入金额度配置无效");
  }

  /** 有界、非零、最多两位小数的金额适用于请求和配置。 */
  private static boolean valid(BigDecimal value) {
    return value!=null && value.signum()>0 && value.scale()>=0 && value.scale()<=2
        && value.compareTo(new BigDecimal("999999999999.99"))<=0;
  }

  /** 先校验金额形状；已有幂等申请不因后续额度或开关变化而失去查询能力。 */
  public BigDecimal amount(String input) {
    if(input==null || !input.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
      throw new IllegalArgumentException("金额需为最多两位小数的正数");
    var value=new BigDecimal(input);
    if(!valid(value)) throw new IllegalArgumentException("模拟入金金额无效");
    return value.setScale(2);
  }

  /** 仅新申请检查当前开关和单笔限制，拒绝时不占额度、不发消息。 */
  public void requireNew(BigDecimal value) {
    if(!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"模拟入金未开启");
    if(value.compareTo(minimum)<0 || value.compareTo(maximum)>0)
      throw new IllegalArgumentException("单笔模拟入金金额超限");
  }
}
