/* 证券登录身份：事务创建用户，PBKDF2 慢哈希保存口令；注册本身绝不配给资金。 */
package com.itranswarp.exchange.ui.service;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

public final class UserService {
  private static final int COST = 600000;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public record User(String id, String email, String name) {}

  public static final class EmailConflict extends RuntimeException {
    /** 明确的身份冲突类型，与不同 Spring 版本的数据库异常分类解耦。 */
    public EmailConflict() {
      super("邮箱已被注册");
    }
  }

  /** 使用证券数据源，嵌套事务可与演示运行登记共同提交。 */
  public UserService(DataSource source) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  /** 规范邮箱与显示名；仅身份入库，不能触发旧 BTC 注册充值逻辑。 */
  public User create(String email, String name, String password) {
    String normalized = email(email);
    if (name == null || name.isBlank() || name.length() > 60)
      throw new IllegalArgumentException("姓名需为 1 至 60 个字符");
    password(password);
    String salt = HexFormat.of().formatHex(random(16));
    String hash = encode(password, salt);
    try {
      return transaction.execute(
          status -> {
            long now = System.currentTimeMillis();
            var key = new GeneratedKeyHolder();
            jdbc.update(
                connection -> {
                    /* 用户主键由数据库分配，不假设演示用户一定是 1000/1001。 */
                  var sql =
                      connection.prepareStatement(
                          "INSERT INTO users(createdAt,type) VALUES (?,'NORMAL')",
                          java.sql.Statement.RETURN_GENERATED_KEYS);
                  sql.setLong(1, now);
                  return sql;
                },
                key);
            long id = Objects.requireNonNull(key.getKey()).longValue();
            jdbc.update(
                "INSERT INTO user_profiles VALUES (?,?,?,?,?)",
                id,
                now,
                normalized,
                name.trim(),
                now);
            jdbc.update("INSERT INTO password_auths VALUES (?,?,?)", id, hash, salt);
            return new User(Long.toString(id), normalized, name.trim());
          });
    } catch (org.springframework.dao.DataIntegrityViolationException failure) {
      // Spring 6.0/驱动可能只返回父异常；仅把邮箱唯一键 1062 转换为可公开的 409。
      Throwable cause = failure.getMostSpecificCause();
      if (cause instanceof java.sql.SQLException sql
          && sql.getErrorCode() == 1062
          && sql.getMessage().contains("user_profiles.email")) throw new EmailConflict();
      throw failure;
    }
  }

  /** 统一错误不泄露邮箱是否存在；缺失账号也执行一次相同成本的哈希。 */
  public User authenticate(String email, String password) {
    String normalized = email(email);
    password(password);
    var rows =
        jdbc.queryForList(
            "SELECT p.userId,p.email,p.name,a.passwd,a.random FROM user_profiles p JOIN"
                + " password_auths a ON a.userId=p.userId WHERE p.email=?",
            normalized);
    String salt =
        rows.isEmpty() ? "00000000000000000000000000000000" : (String) rows.get(0).get("random");
    String actual = encode(password, salt),
        expected = rows.isEmpty() ? "" : (String) rows.get(0).get("passwd");
    if (!MessageDigest.isEqual(
        actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new IllegalArgumentException("邮箱或密码错误");
    var row = rows.get(0);
    return new User(
        row.get("userId").toString(), row.get("email").toString(), row.get("name").toString());
  }

  /** 邮箱限制为常见 ASCII 格式并统一小写，避免身份键产生歧义。 */
  private static String email(String value) {
    if (value == null) throw new IllegalArgumentException("邮箱必填");
    String text = value.trim().toLowerCase(Locale.ROOT);
    if (text.length() > 100 || !text.matches("[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}"))
      throw new IllegalArgumentException("邮箱格式错误");
    return text;
  }

  /** 有界密码长度避免请求消耗无限计算，不截断用户密码。 */
  private static void password(String value) {
    if (value == null || value.length() < 10 || value.length() > 128)
      throw new IllegalArgumentException("密码需为 10 至 128 个字符");
  }

  /** 固定版本和成本写入哈希字段，未来升级可显式识别旧格式。 */
  private static String encode(String password, String salt) {
    var spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), COST, 256);
    try {
      return "pbkdf2$"
          + COST
          + "$"
          + Base64.getEncoder()
              .encodeToString(
                  SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                      .generateSecret(spec)
                      .getEncoded());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("口令算法不可用", failure);
    } finally {
      spec.clearPassword();
    }
  }

  /** 密码、会话防伪令牌仅使用系统加密随机源。 */
  static byte[] random(int size) {
    byte[] value = new byte[size];
    new SecureRandom().nextBytes(value);
    return value;
  }
}
