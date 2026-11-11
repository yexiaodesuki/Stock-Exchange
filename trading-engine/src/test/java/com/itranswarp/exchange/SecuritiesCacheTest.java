/* 真实 Redis 隔离测试：大序号原子比较、证券隔离和一次性票据，只清理随机前缀。 */
package com.itranswarp.exchange;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.lettuce.core.ScanArgs;
import com.itranswarp.exchange.securities.output.MarketCache;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SECURITIES_TEST_REDIS_URI",matches=".+")
class SecuritiesCacheTest {
    MarketCache cache;String namespace;
    /** 每个测试使用不同 Redis 前缀，不复用开发缓存和票据。 */
    @BeforeEach void open(){namespace="securities.test."+UUID.randomUUID().toString().replace("-","");cache=new MarketCache(System.getenv("SECURITIES_TEST_REDIS_URI"),namespace);}
    /** 只扫描并删除本次随机前缀，不执行 FLUSHDB/FLUSHALL。 */
    @AfterEach void close(){try(var connection=cache.client().connect()){var commands=connection.sync();var cursor=commands.scan(ScanArgs.Builder.matches(namespace+":*").limit(100));while(true){if(!cursor.getKeys().isEmpty())commands.del(cursor.getKeys().toArray(String[]::new));if(cursor.isFinished())break;cursor=commands.scan(cursor,ScanArgs.Builder.matches(namespace+":*").limit(100));}}finally{cache.close();}}
    /** Lua 不用 double 比较序号，超过 JavaScript 安全整数后旧数据仍不能覆盖新数据。 */
    @Test void comparesLargeSequenceExactlyAndSeparatesSecurities(){assertTrue(cache.put(1001,"book",9007199254740993L,Map.of("marker","new")));assertFalse(cache.put(1001,"book",9007199254740992L,Map.of("marker","old")));assertTrue(cache.get(1001,"book").orElseThrow().contains("new"));assertTrue(cache.put(1002,"book",1,Map.of("marker","green")));assertTrue(cache.get(1002,"book").orElseThrow().contains("green"));}
    /** 一个票据只给一个连接，客户端不能复用它切换用户身份。 */
    @Test void ticketsAreSingleUse(){String ticket=cache.ticket(2,System.currentTimeMillis()+10000);assertEquals(2,cache.consumeTicket(ticket).userId());assertThrows(IllegalArgumentException.class,()->cache.consumeTicket(ticket));assertThrows(IllegalArgumentException.class,()->cache.ticket(1,System.currentTimeMillis()+1000));}
    /** 原身份过期时拒绝票据，不因 Redis TTL 尚未结束延长登录期限。 */
    @Test void originalIdentityExpiryStillApplies()throws Exception{String ticket=cache.ticket(2,System.currentTimeMillis()+100);Thread.sleep(150);assertThrows(IllegalArgumentException.class,()->cache.consumeTicket(ticket));assertTrue(cache.get(1001,"quotation").isEmpty());}
}
