/* 公共金额输出使用普通十进制文本；特别避免零剩余股数变成 0E-18 破坏页面协议。 */
package com.itranswarp.exchange.web.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;

public final class PlainDecimalSerializer extends JsonSerializer<BigDecimal> {
  /** 只改变表示形式，不舍入价格、数量或改变数值。 */
  @Override
  public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider)
      throws IOException {
    generator.writeString(value.toPlainString());
  }
}
