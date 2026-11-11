/* 模拟入金策略单测：精确小数、单笔边界、功能开关及签名身份验证。 */
package com.itranswarp.exchange.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DepositPolicyTest {
  /** 价格文本必须完整匹配，不能吞后缀、舍入、接受指数或零负数。 */
  @Test void validatesExactMoneyAndLimits() {
    var policy=new DepositPolicy(true,new BigDecimal("1.00"),new BigDecimal("100000.00"),new BigDecimal("500000.00"));
    assertEquals(new BigDecimal("1000.00"),policy.amount("1000"));
    for(String bad:new String[]{"0","-1","1.001","1e3","NaN","1x"," 10","9999999999999"})
      assertThrows(IllegalArgumentException.class,()->policy.amount(bad),bad);
    policy.requireNew(policy.amount("1.00"));policy.requireNew(policy.amount("100000.00"));
    assertThrows(IllegalArgumentException.class,()->policy.requireNew(policy.amount("0.99")));
    assertThrows(IllegalArgumentException.class,()->policy.requireNew(policy.amount("100000.01")));
  }

  /** 关闭开关返回明确拒绝，非法配置不得在启动时静默放宽。 */
  @Test void disabledAndInvalidConfigurationAreRejected() {
    var off=new DepositPolicy(false,BigDecimal.ONE,BigDecimal.TEN,new BigDecimal("100"));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->off.requireNew(BigDecimal.ONE)).getStatusCode().value());
    assertThrows(IllegalArgumentException.class,()->new DepositPolicy(true,BigDecimal.ZERO,BigDecimal.TEN,BigDecimal.ONE));
  }
}
