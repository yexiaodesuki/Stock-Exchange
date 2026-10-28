/* 证券自动迁移：复用原 Flyway 历史和锁；只升级有版本的表，不清库、不修复校验和。 */
package com.itranswarp.exchange.securities;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class SchemaMigration {
    /** 工具类不可实例化。 */
    private SchemaMigration() { }

    /** 在读取证券目录前执行；并发启动由 Flyway 数据库锁串行化，失败向上传播阻止服务就绪。 */
    public static void migrate(DataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/securities")
                .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true)
                .load().migrate();
    }
}
