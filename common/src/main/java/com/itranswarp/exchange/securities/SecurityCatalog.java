/* 证券目录在自动迁移及存储校验后加载，不在 Java 中维护另一份股票清单。 */
package com.itranswarp.exchange.securities;

import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SecurityCatalog {
    private final Map<Long, SecurityDefinition> definitions;

    /** 从同一目录版本构造不可变索引；重复代码或标识立即失败。 */
    public SecurityCatalog(Collection<SecurityDefinition> entries) {
        var byId = new LinkedHashMap<Long, SecurityDefinition>();
        var symbols = new java.util.HashSet<String>();
        for (var entry : entries) {
            if (byId.putIfAbsent(entry.securityId(), entry) != null || !symbols.add(entry.symbol()))
                throw new IllegalArgumentException("重复证券标识或代码");
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("证券目录不能为空");
        this.definitions = Map.copyOf(byId);
    }

    /** 先自动迁移，再检查存储家族和版本；失败阻止业务启动，不生成资产或事件。 */
    public static SecurityCatalog load(DataSource source) {
        SchemaMigration.migrate(source);
        StorageCompatibility.requireSecurities(source);
        return new SecurityCatalog(new JdbcTemplate(source).query("SELECT * FROM securities ORDER BY securityId", (rs, row) ->
                // 每行映射成不可变规则；非法配置由构造器拒绝。
                new SecurityDefinition(rs.getLong("securityId"), rs.getString("symbol"), rs.getString("name"),
                        Currency.valueOf(rs.getString("quoteCurrency")), rs.getBigDecimal("priceTick"),
                        rs.getBigDecimal("quantityStep"), SecurityStatus.valueOf(rs.getString("status")),
                        rs.getInt("definitionVersion"))));
    }

    /** 查询指定证券；缺省标识不能悄悄回退为第一只股票。 */
    public SecurityDefinition require(long securityId) {
        var definition = definitions.get(securityId);
        if (definition == null) throw new IllegalArgumentException("未知证券: " + securityId);
        return definition;
    }

    /** 返回按标识排序的只读目录，供后续查询与页面选择使用。 */
    public java.util.List<SecurityDefinition> all() {
        return definitions.values().stream().sorted(java.util.Comparator.comparingLong(SecurityDefinition::securityId)).toList();
    }
}
