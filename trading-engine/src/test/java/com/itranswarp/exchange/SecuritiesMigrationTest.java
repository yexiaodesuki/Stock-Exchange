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
    /** 在回滚事务内复现旧称呼；重复执行真实迁移 SQL 只改目标名字，不改自定义名及其他字段。 */
    @Test void legacyDisplayNamesChangeWithoutReplacingProfiles() {
        var source=SecuritiesPersistenceTest.source();
        SecurityCatalog.load(source);
        var jdbc=new JdbcTemplate(source);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            // 只在隔离测试库事务中准备旧身份，结束回滚还原所有数据。
            jdbc.update("DELETE FROM user_profiles WHERE email IN ('user1@example.com','user2@example.com','user3@example.com')");
            jdbc.update("INSERT INTO user_profiles VALUES (-101,11,'user1@example.com','演示买方',12),(-102,21,'user2@example.com','演示卖方',22),(-103,31,'user3@example.com','自定义名字',32)");
            String before=jdbc.queryForList("SELECT userId,createdAt,email,updatedAt FROM user_profiles ORDER BY userId").toString();
            for(int i=0;i<2;i++) org.springframework.jdbc.datasource.init.DatabasePopulatorUtils.execute(
                    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                            new org.springframework.core.io.ClassPathResource("db/securities/V4__demo_display_names.sql")),source);
            assertEquals("用户一",jdbc.queryForObject("SELECT name FROM user_profiles WHERE userId=-101",String.class));
            assertEquals("用户二",jdbc.queryForObject("SELECT name FROM user_profiles WHERE userId=-102",String.class));
            assertEquals("自定义名字",jdbc.queryForObject("SELECT name FROM user_profiles WHERE userId=-103",String.class));
            assertEquals(before,jdbc.queryForList("SELECT userId,createdAt,email,updatedAt FROM user_profiles ORDER BY userId").toString());
            jdbc.update("UPDATE user_profiles SET name='我的名字' WHERE userId=-101");
            org.springframework.jdbc.datasource.init.DatabasePopulatorUtils.execute(
                    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                            new org.springframework.core.io.ClassPathResource("db/securities/V4__demo_display_names.sql")),source);
            assertEquals("我的名字",jdbc.queryForObject("SELECT name FROM user_profiles WHERE userId=-101",String.class));
            status.setRollbackOnly();
        });
    }
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
