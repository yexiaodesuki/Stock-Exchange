/* 入金存储：本人幂等、每日行锁、事务发件箱和最终状态更新；不直接读写资产余额。 */
package com.itranswarp.exchange.repository;

import com.itranswarp.exchange.securities.output.OutputStore;
import com.itranswarp.exchange.service.DepositPolicy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class DepositRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  public record Deposit(String depositId,long userId,BigDecimal amount,LocalDate quotaDate,
      String status,String reason,Long sequenceId,long createdAt,long updatedAt) {}

  /** 共享证券数据源；申请、额度与发件箱必须使用同一个本地事务。 */
  public DepositRepository(DataSource source) {
    jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  /** 行转换只包含入金字段，不读取密码、Cookie 或用户资产。 */
  private Deposit row(ResultSet rs,int index) throws SQLException {
    return new Deposit(rs.getString("depositId"),rs.getLong("userId"),rs.getBigDecimal("amount"),
        rs.getDate("quotaDate").toLocalDate(),rs.getString("status"),rs.getString("reason"),
        rs.getObject("sequenceId",Long.class),rs.getLong("createdAt"),rs.getLong("updatedAt"));
  }

  /** 原申请金额不一致时明确 409，不能用同一个键修改资金事件。 */
  private Deposit existing(long user,String hash,BigDecimal amount) {
    var rows=jdbc.query("SELECT * FROM deposit_requests WHERE userId=? AND keyHash=?",this::row,user,hash);
    if(rows.isEmpty()) return null;
    if(rows.get(0).amount().compareTo(amount)!=0) throw new ResponseStatusException(HttpStatus.CONFLICT,"入金幂等键已用于其他金额");
    return rows.get(0);
  }

  /** 预占当日额度和写入申请/发件箱同事务；并发跨日冲突回滚后读取原申请。 */
  public Deposit create(long user,String key,BigDecimal amount,LocalDate date,long now,DepositPolicy policy) {
    if(user<=0 || key==null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw new IllegalArgumentException("入金幂等键无效");
    String hash=OutputStore.hash(key);
    try {
      return transaction.execute(status -> {
        // 旧申请始终可重试，不受新一天额度或功能关闭影响。
        var prior=existing(user,hash,amount);
        if(prior!=null) return prior;
        policy.requireNew(amount);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND type='NORMAL'",Integer.class,user)!=1)
          throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        jdbc.update("INSERT INTO deposit_daily_limits VALUES (?,?,0) ON DUPLICATE KEY UPDATE userId=userId",user,date);
        var reserved=jdbc.queryForObject("SELECT reservedAmount FROM deposit_daily_limits WHERE userId=? AND quotaDate=? FOR UPDATE",BigDecimal.class,user,date);
        // 锁后使用当前读，不能依赖 REPEATABLE READ 的旧快照查重。
        var rows=jdbc.query("SELECT * FROM deposit_requests WHERE userId=? AND keyHash=? FOR UPDATE",this::row,user,hash);
        if(!rows.isEmpty()) {
          if(rows.get(0).amount().compareTo(amount)!=0) throw new ResponseStatusException(HttpStatus.CONFLICT,"入金幂等键已用于其他金额");
          return rows.get(0);
        }
        if(reserved.add(amount).compareTo(policy.daily())>0)
          throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"今日模拟入金额度不足（含待确认申请，UTC 日界线）");
        String id=UUID.randomUUID().toString();
        jdbc.update("UPDATE deposit_daily_limits SET reservedAmount=reservedAmount+? WHERE userId=? AND quotaDate=?",amount,user,date);
        jdbc.update("INSERT INTO deposit_requests VALUES (?,?,?,?,?,'PENDING','',NULL,?,?)",id,user,hash,amount,date,now,now);
        jdbc.update("INSERT INTO deposit_outbox(depositId,nextAttemptAt) VALUES (?,?)",id,now);
        return new Deposit(id,user,amount,date,"PENDING","",null,now,now);
      });
    } catch(org.springframework.dao.DuplicateKeyException duplicate) {
      var prior=existing(user,hash,amount);
      if(prior==null) throw duplicate;
      return prior;
    }
  }

  /** 单条记录始终附带本人条件，猜测他人的 UUID 只得到 404。 */
  public Deposit get(long user,String id) {
    var rows=jdbc.query("SELECT * FROM deposit_requests WHERE userId=? AND depositId=?",this::row,user,id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return rows.get(0);
  }

  /** 最近历史有界读取，分页使用页码，避免同毫秒记录在时间游标分页时遗漏。 */
  public List<Deposit> list(long user,int page,int limit) {
    if(page<0 || page>10000 || limit<1 || limit>100) throw new IllegalArgumentException("入金分页参数无效");
    return jdbc.query("SELECT * FROM deposit_requests WHERE userId=? ORDER BY createdAt DESC,depositId DESC LIMIT ? OFFSET ?",this::row,user,limit,page*limit);
  }

  /** 每轮只读取有限待处理申请，进程崩溃后由持久到期时间恢复。 */
  public List<Deposit> due(long now) {
    return jdbc.query("SELECT r.* FROM deposit_requests r JOIN deposit_outbox o ON o.depositId=r.depositId WHERE r.status='PENDING' AND o.completed=FALSE AND o.nextAttemptAt<=? ORDER BY o.nextAttemptAt,r.depositId LIMIT 50",this::row,now);
  }

  /** 原子推迟下次处理，多实例避免同时领取；超时仍允许相同事件再次投递。 */
  public boolean claim(String id,long now,long retryAt) {
    return jdbc.update("UPDATE deposit_outbox SET nextAttemptAt=?,attempts=attempts+1 WHERE depositId=? AND completed=FALSE AND nextAttemptAt<=?",retryAt,id,now)==1;
  }

  /** 已确认结果才能结束申请；拒绝释放原日期额度，重复确认不重复释放。 */
  public void finish(Deposit deposit,boolean accepted,String reason,long sequence,long now) {
    transaction.executeWithoutResult(status -> {
      // 与创建统一按额度→申请的锁顺序，避免事务相互等待。
      jdbc.queryForObject("SELECT reservedAmount FROM deposit_daily_limits WHERE userId=? AND quotaDate=? FOR UPDATE",BigDecimal.class,deposit.userId(),deposit.quotaDate());
      int changed=jdbc.update("UPDATE deposit_requests SET status=?,reason=?,sequenceId=?,updatedAt=? WHERE depositId=? AND status='PENDING'",
          accepted?"SUCCEEDED":"REJECTED",accepted?"":reason,sequence,now,deposit.depositId());
      if(changed==0) return;
      if(!accepted) jdbc.update("UPDATE deposit_daily_limits SET reservedAmount=reservedAmount-? WHERE userId=? AND quotaDate=?",deposit.amount(),deposit.userId(),deposit.quotaDate());
      jdbc.update("UPDATE deposit_outbox SET completed=TRUE WHERE depositId=?",deposit.depositId());
    });
  }
}
