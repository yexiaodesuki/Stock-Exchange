/* 存储家族检查；不迁移 BTC 数据，不把旧快照自动解释成证券快照。 */
package com.itranswarp.exchange.securities;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class StorageCompatibility {
    /** 工具类不持有连接。 */
    private StorageCompatibility() { }

    /** 新读取器只接受证券 V1 家族、协议 V2 和目录 V1；异常传播阻止业务启动。 */
    public static void requireSecurities(DataSource source) {
        var rows = new JdbcTemplate(source).queryForList(
                "SELECT family, protocolVersion, catalogVersion FROM storage_metadata WHERE id=1");
        if (rows.size() != 1 || !"securities-v1".equals(rows.get(0).get("family"))
                || ((Number) rows.get(0).get("protocolVersion")).intValue() != SecuritiesProtocol.VERSION
                || ((Number) rows.get(0).get("catalogVersion")).intValue() != SecuritiesProtocol.CATALOG_VERSION)
            throw new IllegalStateException("数据库不是兼容的证券版本；禁止启动或重放");
    }

}
