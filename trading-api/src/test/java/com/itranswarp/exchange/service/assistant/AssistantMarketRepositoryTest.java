/* 公开行情隔离库回归：范围端点、稳定成交顺序、跨证券隔离、投影追赶和超量拒绝；全部回滚。 */
package com.itranswarp.exchange.service.assistant;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.AssistantMarketRepository;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="SECURITIES_PHASE2_DB_URL",matches=".+")
class AssistantMarketRepositoryTest {
  /** 真实类型与 SQL 验证；禁止连接开发数据库，不删除或更新任何正式成交。 */
  @Test void snapshotRangeOrderAndFailuresAreExplicit(){
    String url=System.getenv("SECURITIES_PHASE2_DB_URL");if(!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))throw new IllegalStateException("禁止访问开发库");
    var source=new DriverManagerDataSource(url,"securities",System.getenv("SECURITIES_DB_PASSWORD"));SecurityCatalog.load(source);var jdbc=new JdbcTemplate(source);
    new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx->{
      jdbc.update("DELETE FROM ticks");jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=100 WHERE id=1");jdbc.update("UPDATE projection_offsets SET sequenceId=100 WHERE name='quotation'");
      // 故意反向插入同毫秒两笔，主键顺序不能冒充事件成交顺序。
      long base=1_000_000_000_000L+Math.abs(UUID.randomUUID().getMostSignificantBits()%1_000_000_000L);
      insert(jdbc,1001,100,12,3,10,1,base);insert(jdbc,1001,100,10,2,10,0,base+1);insert(jdbc,1001,99,9,1,9,0,base+2);insert(jdbc,1001,200,50,1,11,0,base+3);insert(jdbc,1002,100,99,1,12,0,base+4);
      var repository=new AssistantMarketRepository(source,100);var snapshot=repository.read(List.of(1001L,1003L),100,200);assertEquals(100,snapshot.projectionSequence());var star=snapshot.markets().get(0);assertEquals(2,star.trades().size());assertEquals(0,star.trades().get(0).index());assertEquals(1,star.trades().get(1).index());assertEquals(0,star.last().price().compareTo(new java.math.BigDecimal("12")));assertTrue(snapshot.markets().get(1).trades().isEmpty());assertNull(snapshot.markets().get(1).last());
      var service=new AssistantMarketService(repository,AssistantPhaseTwoTest.catalog());var facts=service.read(1001L,new AssistantTimeRangeResolver.Range(100,200,"测试范围"));assertTrue(facts.get("markets").toString().contains("amount=56.00"));assertFalse(facts.toString().contains("userId"));assertFalse(facts.toString().contains("makerOrderId"));
      var old=repository.read(List.of(1001L),150,200).markets().get(0);assertTrue(old.trades().isEmpty());assertEquals(10,old.last().sequence());
      assertEquals(422,assertThrows(ResponseStatusException.class,()->new AssistantMarketRepository(source,1).read(List.of(1001L),100,200)).getStatusCode().value());
      jdbc.update("UPDATE projection_offsets SET sequenceId=99 WHERE name='quotation'");assertEquals(503,assertThrows(ResponseStatusException.class,()->repository.read(List.of(1003L),100,200)).getStatusCode().value());tx.setRollbackOnly();
    });
  }
  /** 仅向随机库注入公开成交夹具，不制造资金事件或修改引擎账户。 */
  private void insert(JdbcTemplate jdbc,long security,long time,int price,int quantity,long sequence,int index,long order){jdbc.update("INSERT INTO ticks(securityId,createdAt,makerOrderId,takerOrderId,price,quantity,sequenceId,takerDirection,tradeIndex) VALUES (?,?,?,?,?,?,?,TRUE,?)",security,time,order,order+100,price,quantity,sequence,index);}
}
