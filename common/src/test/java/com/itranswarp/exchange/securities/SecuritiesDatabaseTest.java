/* 显式开启的证券数据库集成测试；所有测试写入均在同一连接事务中回滚。 */
package com.itranswarp.exchange.securities;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SECURITIES_DB_TESTS", matches="true")
class SecuritiesDatabaseTest {
    /** 只允许显式证券数据库地址；绝不回退连接旧 exchange 数据库。 */
    private DriverManagerDataSource source() {
        String url = System.getenv("SECURITIES_JDBC_URL");
        if (url == null || !url.contains("/exchange_securities?")) throw new IllegalStateException("必须指定隔离证券数据库");
        return new DriverManagerDataSource(url, "securities", System.getenv("SECURITIES_DB_PASSWORD"));
    }

    /** 证券目录与数据库家族必须正确，错误库不能被默认为空账户。 */
    @Test void loadsCatalogAndChecksStorageFamily() {
        var ds=source();assertEquals(5,SecurityCatalog.load(ds).all().size());
        assertEquals("星河科技",SecurityCatalog.load(ds).require(1001).name());
        StorageCompatibility.requireSecurities(ds);
    }

    /** 篡改版本只在测试事务中可见；只读守卫拒绝后回滚，不能在此事务里调用 DDL 迁移。 */
    @Test void rejectsWrongStoredVersion() throws Exception {
        try (var connection = source().getConnection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.executeUpdate("UPDATE storage_metadata SET catalogVersion=99 WHERE id=1");
                var transactional = new SingleConnectionDataSource(connection, true);
                assertThrows(IllegalStateException.class, () -> StorageCompatibility.requireSecurities(transactional));
            } finally { connection.rollback(); }
        }
    }

    /** 五证券可在同一时间分别生成 K线；同证券同周期同时间拒绝重复。 */
    @Test void enforcesPerSecurityBarIdentity() throws Exception {
        try (var connection = source().getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String table : new String[]{"sec_bars","min_bars","hour_bars","day_bars"}) {
                    for (long security = 1001; security <= 1005; security++) insertBar(connection, table, security);
                    assertThrows(SQLException.class, () -> insertBar(connection, table, 1001));
                    assertThrows(SQLException.class, () -> insertBar(connection, table, 9999));
                }
            } finally { connection.rollback(); }
        }
    }

    /** 事务内写入特定测试时间，调用者保证回滚，不保留伪造行情。 */
    private void insertBar(Connection connection, String table, long security) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + table
                + " (securityId,startTime,openPrice,highPrice,lowPrice,closePrice,quantity) VALUES (?,9223372036000,10,10,10,10,1)")) {
            statement.setLong(1, security);
            statement.executeUpdate();
        }
    }

    /** 数据库也拒绝旧事件/快照格式和双重资产类型；相同幂等键拒绝重复。 */
    @Test void enforcesVersionsAndIdempotency() throws Exception {
        try (var connection = source().getConnection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO events VALUES (9223372036000,0,1,1,1,'{}')"));
                assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO engine_snapshots(sequenceId,formatVersion,catalogVersion,snapshotData,checksum,createdAt) VALUES (0,1,1,'{}',REPEAT('a',64),1)"));
                sql.executeUpdate("INSERT INTO unique_events VALUES ('phase1:rollback-test',1,9223372036000)");
                assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO unique_events VALUES ('phase1:rollback-test',2,9223372036001)"));
                assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO transfer_logs VALUES ('phase1:rollback-test',10,'CASH','USD',1001,1,1,'OK','TEST',1)"));
            } finally { connection.rollback(); }
        }
    }
}
