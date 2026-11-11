/* 助手历史只读投影：固定本人条件、固定列、有界查询，不接受 SQL 或对手用户标识。 */
package com.itranswarp.exchange.repository;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

public final class AssistantReadRepository {
  private final JdbcTemplate jdbc;
  private final org.springframework.transaction.support.TransactionTemplate read;
  /** 独立模板只约束助手查询，不改变原订单查询的超时配置。 */
  public AssistantReadRepository(DataSource source){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(1);jdbc.setMaxRows(201);read=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);}
  /** 先验本人归属再读明细；超出明细窗口明确拒绝，不以截断结果计算总额。 */
  public Object order(long user,long security,long order){
    var rows=jdbc.queryForList("SELECT id,securityId,direction,price,quantity,unfilledQuantity,status,updatedAt,sequenceId FROM orders WHERE userId=? AND securityId=? AND id=?",user,security,order);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var matches=jdbc.queryForList("SELECT price,quantity,direction,createdAt,sequenceId FROM match_details WHERE userId=? AND securityId=? AND orderId=? ORDER BY sequenceId,id LIMIT 201",user,security,order);
    if(matches.size()>200)throw new IllegalArgumentException("成交明细过多，请使用原历史页面");
    return Map.of("order",rows.get(0),"matches",matches);
  }
  /** 本人已结束订单汇总与最近20条分开，活动状态以引擎为准，不把投影延迟当作当前挂单。 */
  public Object finished(long user,Long security){
    return read.execute(tx->{
    // 总数与窗口共享 MySQL 可重复读视图，不会因中间新增成交而各算各的。
    String where=" WHERE userId=? AND status IN ('FULLY_FILLED','FULLY_CANCELLED','PARTIAL_CANCELLED')";
    Object[] args=security==null?new Object[]{user}:new Object[]{user,security};
    if(security!=null)where+=" AND securityId=?";
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM orders"+where,Long.class,args);
    var rows=jdbc.queryForList("SELECT id,securityId,direction,price,quantity,unfilledQuantity,status,updatedAt FROM orders"+where+" ORDER BY updatedAt DESC,id DESC LIMIT 20",args);
    return Map.of("total",Long.toString(total),"records",rows,"limit",20);
    });
  }
}
