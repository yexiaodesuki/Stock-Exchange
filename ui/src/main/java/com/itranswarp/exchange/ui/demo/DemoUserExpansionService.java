/* 旧两用户演示环境的幂等补齐：保护原账户，只登记八个新身份及四十八个真实配资步骤。 */
package com.itranswarp.exchange.ui.demo;

import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.StorageCompatibility;
import com.itranswarp.exchange.securities.output.*;
import com.itranswarp.exchange.securities.persistence.ProcessLease;
import com.itranswarp.exchange.ui.service.UserService;
import com.itranswarp.exchange.util.JsonUtil;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class DemoUserExpansionService {
  public static final String RUN = "demo-users-v3";
  private static final int STEPS = 48;
  private final DataSource source;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final UserService users;
  private final List<String> passwords;

  /** 真实执行器必须返回已持久化且已经被引擎应用的结果；测试可注入同协议执行器。 */
  @FunctionalInterface
  public interface Executor {
    /** 执行同一个稳定命令，失败向上抛出以保留可续跑现场。 */
    ExecutionEnvelope execute(Submission request) throws Exception;
  }

  /** 不在构造时写数据；密码仅用于首次创建缺少的八个用户。 */
  public DemoUserExpansionService(DataSource source, UserService users, List<String> passwords) {
    this.source = source;
    this.users = users;
    this.passwords = List.copyOf(passwords);
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  /** 独占补齐计划；完成后重复运行不重置密码、不再发事件，也不比较用户后来交易后的余额。 */
  public void expand(Executor executor) throws Exception {
    boolean acquired = false;
    try (var lease = new ProcessLease(source, RUN)) {
      acquired = true;
      StorageCompatibility.requireSecurities(source);
      lease.check();
      prepare();
      var plan = readPlan();
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM bootstrap_runs WHERE id=? AND status='COMPLETE'",
              Integer.class,
              RUN)
          == 1) {
        if (plan.stream().anyMatch(row -> !row.get("status").equals("COMPLETE")))
          throw new IllegalStateException("完成记录与步骤不一致");
        return;
      }
      for (var row : plan) {
        lease.check();
        if (row.get("status").equals("COMPLETE")) continue;
        var request = JsonUtil.readJson((String) row.get("submissionData"), Submission.class);
        var result = executor.execute(request);
        if (!result.accepted()
            || result.sequenceId() <= 0
            || result.userId() != request.userId()
            || !result.uniqueId().equals(request.uniqueId())
            || !result.trades().isEmpty()
            || !result.orders().isEmpty()
            || !result.books().isEmpty()) throw new IllegalStateException("配资结果不符合计划");
        jdbc.update(
            "UPDATE demo_steps SET sequenceId=?,status='COMPLETE',updatedAt=? WHERE stepId=? AND"
                + " runId=?",
            result.sequenceId(),
            System.currentTimeMillis(),
            request.uniqueId(),
            RUN);
      }
      lease.check();
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM demo_steps WHERE runId=? AND status='COMPLETE'",
              Integer.class,
              RUN)
          != STEPS) throw new IllegalStateException("补齐步骤尚未完整执行");
      jdbc.update(
          "UPDATE bootstrap_runs SET status='COMPLETE',updatedAt=? WHERE id=?",
          System.currentTimeMillis(),
          RUN);
    } catch (Exception failure) {
      if (acquired)
        try {
          jdbc.update(
              "UPDATE bootstrap_runs SET status='FAILED',updatedAt=? WHERE id=? AND"
                  + " status<>'COMPLETE'",
              System.currentTimeMillis(),
              RUN);
        } catch (Exception unavailable) {
          /* 保留原始失败，不通过清库处理不确定进度。 */
        }
      throw failure;
    }
  }

  /** 身份、运行记录和完整计划同事务创建；任何邮箱冲突或密码错误都整体回滚。 */
  private void prepare() {
    transaction.executeWithoutResult(
        status -> {
          var runs =
              jdbc.queryForList(
                  "SELECT version,status FROM bootstrap_runs WHERE id=? FOR UPDATE", RUN);
          if (!runs.isEmpty()) {
            if (((Number) runs.get(0).get("version")).intValue() != 3)
              throw new IllegalStateException("补齐版本不兼容");
            if (!runs.get(0).get("status").equals("COMPLETE"))
              jdbc.update(
                  "UPDATE bootstrap_runs SET status='RUNNING',updatedAt=? WHERE id=?",
                  System.currentTimeMillis(),
                  RUN);
            return;
          }
          if (jdbc.queryForObject(
                      "SELECT COUNT(*) FROM bootstrap_runs WHERE id='demo-v1' AND version=1 AND"
                          + " status='COMPLETE'",
                      Integer.class)
                  != 1
              || jdbc.queryForObject(
                      "SELECT COUNT(*) FROM bootstrap_runs WHERE status<>'COMPLETE'", Integer.class)
                  != 0
              || jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class) != 2
              || jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles", Integer.class) != 2
              || jdbc.queryForObject(
                      "SELECT COUNT(*) FROM user_profiles WHERE email IN"
                          + " ('buyer@example.com','seller@example.com')",
                      Integer.class)
                  != 2) throw new IllegalStateException("只允许补齐已完成的旧两用户演示环境，禁止接管其他账户");
          if (passwords.size() != 10) throw new IllegalArgumentException("需要十个本地用户密码字段");
          String[] names = {"用户一", "用户二", "用户三", "用户四", "用户五", "用户六", "用户七", "用户八", "用户九", "用户十"};
          jdbc.update(
              "INSERT INTO bootstrap_runs VALUES (?,3,'RUNNING',?)",
              RUN,
              System.currentTimeMillis());
          for (int slot = 3; slot <= 10; slot++) {
            long user =
                Long.parseLong(
                    users
                        .create(
                            "user" + slot + "@example.com",
                            names[slot - 1],
                            passwords.get(slot - 1))
                        .id());
            for (var request : requests(slot, user)) {
              String data = JsonUtil.writeJson(request);
              Long security =
                  request.command() instanceof CreditPosition credit ? credit.securityId() : null;
              jdbc.update(
                  "INSERT INTO"
                      + " demo_steps(stepId,runId,userId,securityId,commandHash,submissionData,sequenceId,status,updatedAt)"
                      + " VALUES (?,?,?,?,?,?,NULL,'PENDING',?)",
                  request.uniqueId(),
                  RUN,
                  user,
                  security,
                  OutputStore.hash(data),
                  data,
                  System.currentTimeMillis());
            }
          }
        });
  }

  /** 由持久用户标识重建预期计划并检查校验和，防止续跑时给错误身份或错误资产配资。 */
  private List<Map<String, Object>> readPlan() {
    var expected = new HashMap<String, Submission>();
    for (int slot = 3; slot <= 10; slot++) {
      var identities =
          jdbc.queryForList(
              "SELECT userId FROM user_profiles WHERE email=?", "user" + slot + "@example.com");
      if (identities.size() != 1) throw new IllegalStateException("补齐用户身份缺失");
      for (var request : requests(slot, ((Number) identities.get(0).get("userId")).longValue()))
        expected.put(request.uniqueId(), request);
    }
    var rows = jdbc.queryForList("SELECT * FROM demo_steps WHERE runId=? ORDER BY stepId", RUN);
    if (rows.size() != STEPS) throw new IllegalStateException("补齐计划缺失");
    for (var row : rows) {
      var request = expected.remove((String) row.get("stepId"));
      if (request == null || request.userId() != ((Number) row.get("userId")).longValue())
        throw new IllegalStateException("补齐步骤身份不一致");
      String data = (String) row.get("submissionData");
      Long security =
          request.command() instanceof CreditPosition credit ? credit.securityId() : null;
      if (!OutputStore.hash(data).equals(row.get("commandHash"))
          || !data.equals(JsonUtil.writeJson(request))
          || !Objects.equals(security, row.get("securityId"))
          || !Set.of("PENDING", "COMPLETE").contains(row.get("status"))
          || (row.get("status").equals("COMPLETE") && !(row.get("sequenceId") instanceof Number)))
        throw new IllegalStateException("补齐计划内容或进度损坏，禁止继续");
    }
    return rows;
  }

  /** 每人固定一个 USD 和五个持仓命令，不创建订单或模拟成交，稳定标识保证重试不会重复配资。 */
  private List<Submission> requests(int slot, long user) {
    var result = new ArrayList<Submission>();
    String prefix = RUN + ":" + String.format(Locale.ROOT, "%03d", slot);
    result.add(
        new Submission(
            2,
            1,
            prefix + ":CASH",
            user,
            new CreditCash(
                com.itranswarp.exchange.securities.Currency.USD, new BigDecimal("100000"))));
    for (long security = 1001; security <= 1005; security++)
      result.add(
          new Submission(
              2,
              1,
              prefix + ":POSITION:" + security,
              user,
              new CreditPosition(security, new BigDecimal("1000"))));
    return result;
  }

  /** 仅供显式维护：--apply 才执行，读取本地私密文件，不启动第二个 Web 应用、不打印密码。 */
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !args[0].equals("--apply"))
      throw new IllegalArgumentException("需显式传入 --apply，工作目录设为项目根目录");
    var config = new Properties();
    try (var reader =
        Files.newBufferedReader(Path.of("build/.env.securities"), StandardCharsets.UTF_8)) {
      config.load(reader);
    }
    String url =
        value(
            config,
            "SECURITIES_JDBC_URL",
            "jdbc:mysql://127.0.0.1:"
                + value(config, "SEC_MYSQL_PORT", "13306")
                + "/exchange_securities?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
    var source =
        new DriverManagerDataSource(url, "securities", value(config, "SEC_MYSQL_PASSWORD", ""));
    var passwords = new ArrayList<String>();
    for (int i = 1; i <= 10; i++)
      passwords.add(value(config, "SECURITIES_DEMO_USER" + i + "_PASSWORD", ""));
    var service = new DemoUserExpansionService(source, new UserService(source), passwords);
    try (var credits =
        new DemoCreditService(
            source,
            value(
                config,
                "SEC_KAFKA_BOOTSTRAP_SERVERS",
                "127.0.0.1:" + value(config, "SEC_KAFKA_PORT", "19092")),
            value(config, "SECURITIES_NAMESPACE", "securities.v1"),
            value(config, "SECURITIES_ENGINE_URL", "http://127.0.0.1:8102"))) {
      service.expand(credits::execute);
    }
    System.out.println("旧演示用户已补齐至十人；原账户保留，重复执行不会重复配资。");
  }

  /** 本地维护参数优先环境变量，其次同一私密配置文件，不执行任何配置内容。 */
  private static String value(Properties config, String key, String fallback) {
    String environment = System.getenv(key);
    return environment == null ? config.getProperty(key, fallback) : environment;
  }
}
