/* 金融 JSON 协议回归：零值与极大值必须是普通十进制文本，浏览器不需要解析科学计数法。 */
package com.itranswarp.exchange.web.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecuritiesSerializationTest {
  /** 完全成交后的 SQL DECIMAL 零值曾显示成 0E-18，此处固定公开表示且不舍入。 */
  @Test
  void writesPlainDecimalWithoutRounding() throws Exception {
    var module = new SimpleModule().addSerializer(BigDecimal.class, new PlainDecimalSerializer());
    var mapper = new ObjectMapper().registerModule(module);
    var value =
        mapper.readTree(
            mapper.writeValueAsString(
                Map.of(
                    "remaining",
                    new BigDecimal("0E-18"),
                    "price",
                    new BigDecimal("100.010000000000000000"),
                    "large",
                    new BigDecimal("1E+18"))));
    assertEquals("0.000000000000000000", value.path("remaining").asText());
    assertEquals("100.010000000000000000", value.path("price").asText());
    assertEquals("1000000000000000000", value.path("large").asText());
  }
}
