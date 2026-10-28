/* 开发版单实例互斥：专属 MySQL 会话锁，失去连接即停止使用；不是生产高可用 fencing。 */
package com.itranswarp.exchange.securities.persistence;

import java.sql.Connection;
import javax.sql.DataSource;

public final class ProcessLease implements AutoCloseable {
    private final Connection connection;
    private final String name;

    /** 非阻塞取得白名单角色的会话锁；用户补齐使用独立角色，不争用正在运行的引擎。 */
    public ProcessLease(DataSource source, String role) {
        if (!java.util.Set.of("engine", "sequencer","quotation","push","demo","demo-users-v3").contains(role)) throw new IllegalArgumentException("实例角色无效");
        Connection opened = null;
        try {
            opened = source.getConnection();
            // MySQL 命名锁最长 64 字符，测试库长名称也必须落在限制内。
            this.name = "sec:" + role + ":" + java.util.UUID.nameUUIDFromBytes(opened.getCatalog().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (var sql = opened.prepareStatement("SELECT GET_LOCK(?,0)")) {
                sql.setString(1, name);
                try (var result = sql.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("已有证券 " + role + " 实例运行");
                }
            }
            this.connection = opened;
        } catch (Exception failure) {
            if (opened != null) try { opened.close(); } catch (Exception ignored) { /* 保留原始启动错误。 */ }
            throw new IllegalStateException("无法取得证券实例锁", failure);
        }
    }

    /** 每批处理及保存前核对仍持有锁；数据库会话丢失后禁止继续执行。 */
    public synchronized void check() {
        try (var sql = connection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            sql.setString(1, name);
            try (var result = sql.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("证券实例锁已失效");
            }
        } catch (Exception failure) { throw new IllegalStateException("证券实例锁连接失效", failure); }
    }

    /** 释放本会话持有的命名锁，再关闭连接；连接池归还前不能遗留锁。 */
    @Override public synchronized void close() {
        try (var sql = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) { sql.setString(1, name); sql.execute(); }
        catch (Exception ignored) { /* 连接已断时 MySQL 自动释放会话锁。 */ }
        finally { try { connection.close(); } catch (Exception ignored) { /* 关闭时不掩盖原错误。 */ } }
    }
}
