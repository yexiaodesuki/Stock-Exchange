/* 自动迁移回归：只使用测试运行器的随机库，检查迁移版本及重复/并发加载不改变业务数据。 */
package com.itranswarp.exchange;

import com.itranswarp.exchange.securities.SecurityCatalog;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SECURITIES_PHASE2_DB_URL", matches=".+")
class SecuritiesMigrationTest {
    /** 空库或已迁移库均由相同入口处理；不重复插入证券，也不删除事件或快照。 */
    @Test void migrationIsRepeatableAndConcurrent() throws Exception {
        var source=SecuritiesPersistenceTest.source();
        assertEquals(5,SecurityCatalog.load(source).all().size());
        var jdbc=new JdbcTemplate(source);
        String before=fingerprint(jdbc);
        var executor=Executors.newFixedThreadPool(3);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            // 多个应用同时启动时，数据库锁保证迁移按顺序校验。
            for(int i=0;i<3;i++) jobs.add(()->SecurityCatalog.load(source).all().size());
            for(var result:executor.invokeAll(jobs)) assertEquals(5,result.get());
        } finally { executor.shutdownNow(); }
        assertEquals(before,fingerprint(jdbc));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3')",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='engine_snapshots'",Integer.class));
    }

    /** 只读比较证券、事件、快照和迁移历史，不能以进程启动成功替代数据保持断言。 */
    private String fingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT CONCAT_WS(':',(SELECT COUNT(*) FROM securities),(SELECT COUNT(*) FROM events),(SELECT COUNT(*) FROM engine_snapshots),(SELECT GROUP_CONCAT(CONCAT(version,'=',checksum) ORDER BY installed_rank) FROM flyway_schema_history WHERE success=1))",String.class);
    }
}
