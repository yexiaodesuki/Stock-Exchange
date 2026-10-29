/* 入金真实 MySQL 回归：随机库内验证并发额度、事务回滚、幂等和发件箱崩溃窗口；不冒充真实引擎测试。 */
package com.itranswarp.exchange.service;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.DepositRepository;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="SECURITIES_PHASE2_DB_URL",matches=".+")
class DepositIntegrationTest {
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private DepositRepository repository;
  private DepositService service;
  private DepositPolicy policy;
  private EventJournal journal;
  private OutputStore outputs;
  private static final long USER=700001,OTHER=700002;
  private static final long NOW=Instant.parse("2026-10-29T23:59:00Z").toEpochMilli();

  /** 拒绝开发库，自动迁移后只清理隔离测试资源；两测试身份不是开发用户。 */
  @BeforeEach void prepare() {
    String url=System.getenv("SECURITIES_PHASE2_DB_URL");
    if(!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))throw new IllegalStateException("禁止测试开发库");
    source=new DriverManagerDataSource(url,"securities",System.getenv("SECURITIES_DB_PASSWORD"));
    var catalog=SecurityCatalog.load(source);jdbc=new JdbcTemplate(source);
    for(String table:List.of("deposit_outbox","deposit_requests","deposit_daily_limits","event_outbox","engine_results","unique_events","events","transfer_logs"))jdbc.update("DELETE FROM "+table);
    jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=0 WHERE id=1");
    jdbc.update("INSERT INTO users VALUES (?,1,'NORMAL'),(?,1,'NORMAL') ON DUPLICATE KEY UPDATE type='NORMAL'",USER,OTHER);
    repository=new DepositRepository(source);policy=new DepositPolicy(true,new BigDecimal("1.00"),new BigDecimal("100000.00"),new BigDecimal("500000.00"));
    service=new DepositService(repository,policy,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
    journal=new EventJournal(source,catalog);outputs=new OutputStore(source);
  }

  /** 不向其他测试遗留申请、身份或事件；删除范围限于已核验随机库。 */
  @AfterEach void cleanup() {
    if(jdbc==null)return;
    for(String table:List.of("deposit_outbox","deposit_requests","deposit_daily_limits","event_outbox","engine_results","unique_events","events","transfer_logs"))jdbc.update("DELETE FROM "+table);
    jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=0 WHERE id=1");
    jdbc.update("DELETE FROM users WHERE id IN (?,?)",USER,OTHER);
  }

  /** 相同金额规范化和跨日/关闭后的重试仍是原申请，不再次占用额度；外人读取 404。 */
  @Test void idempotencyOwnershipAndCrossDay() {
    var first=service.create(USER,"same","1000.0");
    assertEquals(first.depositId(),service.create(USER,"same","1000.00").depositId());
    assertEquals(409,assertThrows(ResponseStatusException.class,()->service.create(USER,"same","1001")).getStatusCode().value());
    var tomorrow=new DepositService(repository,new DepositPolicy(false,BigDecimal.ONE,new BigDecimal("100000"),new BigDecimal("500000")),Clock.fixed(Instant.ofEpochMilli(NOW+120000),ZoneOffset.UTC));
    assertEquals(first.depositId(),tomorrow.create(USER,"same","1000").depositId());
    assertEquals(403,assertThrows(ResponseStatusException.class,()->tomorrow.create(USER,"new","10")).getStatusCode().value());
    assertEquals(404,assertThrows(ResponseStatusException.class,()->service.get(OTHER,first.depositId())).getStatusCode().value());
    assertEquals(1,repository.list(USER,0,20).size());assertTrue(repository.list(OTHER,0,20).isEmpty());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM deposit_outbox",Integer.class));
    assertEquals(0,new BigDecimal("1000").compareTo(jdbc.queryForObject("SELECT SUM(reservedAmount) FROM deposit_daily_limits",BigDecimal.class)));
  }

  /** 同日并发不同键不能超额度，相同键并发也只产生一条申请和发件箱。 */
  @Test void concurrentLimitsAndSameKey() throws Exception {
    var executor=Executors.newFixedThreadPool(8);
    try {
      var jobs=new ArrayList<Callable<Boolean>>();
      for(int i=0;i<8;i++){final String key="concurrent-"+i;jobs.add(()->{try{service.create(USER,key,"100000");return true;}catch(ResponseStatusException rejected){assertEquals(429,rejected.getStatusCode().value());return false;}});}
      int accepted=0;for(var result:executor.invokeAll(jobs))if(result.get())accepted++;
      assertEquals(5,accepted);
      jobs.clear();for(int i=0;i<8;i++)jobs.add(()->{service.create(OTHER,"one-key","10");return true;});
      for(var result:executor.invokeAll(jobs))assertTrue(result.get());
      assertEquals(1,repository.list(OTHER,0,20).size());
      assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM deposit_outbox",Integer.class));
    }finally{executor.shutdownNow();}
  }

  /** 发件箱插入失败时申请和额度同时回滚，不留下不能自动投递的半申请。 */
  @Test void registrationRollback() {
    jdbc.execute("ALTER TABLE deposit_outbox ADD CONSTRAINT isolated_outbox_failure CHECK (attempts<0)");
    try{assertThrows(org.springframework.dao.DataAccessException.class,()->service.create(USER,"rollback","10"));}
    finally{jdbc.execute("ALTER TABLE deposit_outbox DROP CHECK isolated_outbox_failure");}
    assertTrue(repository.list(USER,0,20).isEmpty());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM deposit_daily_limits",Integer.class));
  }

  /** 数据库登记后、消息发送后以及执行结果落库后的故障均续跑同一事件；应用落后时不报成功。 */
  @Test void outboxSurvivesUncertainSendAndDelayedApply() {
    var deposit=service.create(USER,"recover","1000");
    var position=new AtomicLong(0);var sent=new ArrayList<Submission>();
    try(var failed=new DepositDispatchService(repository,outputs,position::get,request->{throw new IllegalStateException("模拟发送前崩溃");})){
      assertThrows(IllegalStateException.class,()->failed.dispatch(NOW));
    }
    assertEquals("PENDING",service.get(USER,deposit.depositId()).status());
    try(var worker=new DepositDispatchService(repository,outputs,position::get,sent::add)) {
      worker.dispatch(NOW+6000);worker.dispatch(NOW+12000);
      assertEquals(2,sent.size());assertEquals(sent.get(0).uniqueId(),sent.get(1).uniqueId());
      var event=journal.append(sent.get(0));assertEquals(event.sequenceId(),journal.append(sent.get(1)).sequenceId());
      var result=new ExecutionEnvelope(event.sequenceId(),event.previousId(),event.userId(),event.uniqueId(),event.createdAt(),true,"OK",null,List.of(),List.of(),List.of());
      outputs.save(event,result,false);
      worker.dispatch(NOW+18000);assertEquals("PENDING",service.get(USER,deposit.depositId()).status());
      position.set(event.sequenceId());worker.dispatch(NOW+24000);
      assertEquals("SUCCEEDED",service.get(USER,deposit.depositId()).status());
      worker.dispatch(NOW+30000);assertEquals(2,sent.size());
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM transfer_logs",Integer.class));
      assertTrue(repository.due(NOW+36000).isEmpty());
    }
  }

  /** 拒绝只释放一次原日期预占，新一天额度独立；分页范围受限。 */
  @Test void rejectionReleasesOnlyOnceAndNextDayHasOwnLimit() {
    var deposit=service.create(USER,"reject","100000");
    repository.finish(deposit,false,"REJECTED",1,NOW);repository.finish(deposit,false,"REJECTED",1,NOW);
    assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT reservedAmount FROM deposit_daily_limits WHERE userId=?",BigDecimal.class,USER)));
    var tomorrow=new DepositService(repository,policy,Clock.fixed(Instant.ofEpochMilli(NOW+120000),ZoneOffset.UTC));
    tomorrow.create(USER,"tomorrow","100000");
    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM deposit_daily_limits",Integer.class));
    assertEquals("REJECTED",service.get(USER,deposit.depositId()).status());
    assertThrows(IllegalArgumentException.class,()->service.list(USER,-1,20));
  }
}
