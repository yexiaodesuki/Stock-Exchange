/* 第三阶段公开协议与身份单元测试，不连接真实账户或消息资源。 */
package com.itranswarp.exchange.securities;

import org.junit.jupiter.api.Test;
import com.itranswarp.exchange.securities.output.*;
import static org.junit.jupiter.api.Assertions.*;

class SecuritiesWireTest {
    /** 证券用途与常量时间校验拒绝篡改及旧格式，签发入口没有对应公开 HTTP API。 */
    @Test void tokenRejectsTamperingAndWrongPurpose(){var tokens=new SecuritiesTokens("test-key-012345678901234567890123456789");String token=tokens.issue(2,System.currentTimeMillis()+60000);assertEquals(2,tokens.verify(token));assertThrows(IllegalArgumentException.class,()->tokens.verify(token.substring(0,token.length()-3)+"XYZ"));assertThrows(IllegalArgumentException.class,()->tokens.verify(java.util.Base64.getUrlEncoder().encodeToString("2:9999999999999:btc:fake".getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
    /** 不接受短密钥、负债身份、已过期或无限期令牌。 */
    @Test void tokenBoundsAreExplicit(){assertThrows(IllegalArgumentException.class,()->new SecuritiesTokens("password"));var tokens=new SecuritiesTokens("test-key-012345678901234567890123456789");assertThrows(IllegalArgumentException.class,()->tokens.issue(1,System.currentTimeMillis()+1000));assertThrows(IllegalArgumentException.class,()->tokens.issue(2,System.currentTimeMillis()-1));assertThrows(IllegalArgumentException.class,()->tokens.issue(2,System.currentTimeMillis()+7200000));}
    /** 大于浏览器安全整数的序号和高精度金额必须原样作为字符串传输。 */
    @Test void preservesExactWireNumbers(){String output=WireJson.text("{\"sequenceId\":9007199254740993,\"price\":1234567890.123456789,\"ok\":true}");assertTrue(output.contains("\"9007199254740993\""));assertTrue(output.contains("\"1234567890.123456789\""));assertTrue(output.contains("\"ok\":true"));}
}
