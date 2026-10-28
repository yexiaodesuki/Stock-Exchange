/* 只清理运行器创建的随机临时库测试内容；开发库和证券目录不在清理范围。 */
package com.itranswarp.exchange;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

final class TestDatabase {
    /** 工具类不实例化。 */
    private TestDatabase(){}
    /** 核对测试库后自动迁移空库，再复位白名单表，禁止操作开发库。 */
    static void reset(DataSource source){var jdbc=new JdbcTemplate(source);String name=jdbc.queryForObject("SELECT DATABASE()",String.class);if(!name.matches("exchange_securities_test_[a-f0-9]{32}"))throw new IllegalStateException("禁止清理非测试库");
        com.itranswarp.exchange.securities.SchemaMigration.migrate(source);
        for(String table:new String[]{"event_outbox","engine_results","api_requests","engine_snapshots","unique_events","events","orders","match_details","clearings","transfer_logs","ticks","sec_bars","min_bars","hour_bars","day_bars"})jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE engine_output_checkpoint SET sequenceId=0 WHERE id=1");jdbc.update("UPDATE projection_offsets SET sequenceId=0 WHERE name='quotation'");
    }
}
