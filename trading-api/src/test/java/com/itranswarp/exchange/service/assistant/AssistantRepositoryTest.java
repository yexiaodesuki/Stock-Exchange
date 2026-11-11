/* 随机隔离库的本人订单汇总回归：跨证券总数、最近窗口及他人隔离，事务结束全部回滚。 */
package com.itranswarp.exchange.service.assistant;
import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.AssistantReadRepository;
import com.itranswarp.exchange.securities.SecurityCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="SECURITIES_PHASE2_DB_URL",matches=".+")
class AssistantRepositoryTest {
  /** 只操作运行器的随机库，超过20条时仍报告准确总数，其他用户记录绝不进入结果。 */
  @Test void totalsWindowAndOwnerAreIndependent(){String url=System.getenv("SECURITIES_PHASE2_DB_URL");if(!url.matches("jdbc:mysql://[^/]+/exchange_securities_test_[a-f0-9]{32}\\?.+"))throw new IllegalStateException("禁止访问开发库");var source=new DriverManagerDataSource(url,"securities",System.getenv("SECURITIES_DB_PASSWORD"));SecurityCatalog.load(source);var jdbc=new JdbcTemplate(source);var repository=new AssistantReadRepository(source);new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx->{long base=1_000_000_000_000L+Math.abs(UUID.randomUUID().getMostSignificantBits()%1_000_000_000L),user=base+1000;for(int i=0;i<28;i++)jdbc.update("INSERT INTO orders VALUES (?,?,?,?,?,?,'BUY',10,10,0,?)",base+i,i==25?1002:1001,i==26?user+1:user,i+1,1,i+1,i==27?"PENDING":"FULLY_FILLED");var all=(Map<?,?>)repository.finished(user,null);assertEquals("26",all.get("total"));assertEquals(20,((List<?>)all.get("records")).size());var star=(Map<?,?>)repository.finished(user,1001L);assertEquals("25",star.get("total"));assertEquals("1",((Map<?,?>)repository.finished(user,1002L)).get("total"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repository.order(user,1001,base+26));tx.setRollbackOnly();});}
}
