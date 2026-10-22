/* 行情观测回归：模拟处理中异常和拒绝，不写真实 Redis 或 MySQL。 */
package com.itranswarp.exchange.quotation;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.itranswarp.exchange.message.TickMessage;
import com.itranswarp.exchange.monitoring.PipelineMonitoring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class QuotationMonitoringTest {
    /** 真实行情方法被 Redis 拒绝时不访问数据库，不把已赋值的业务序号记成成功。 */
    @Test void redisRejectionDoesNotAdvanceSuccess() {
        var registry = new SimpleMeterRegistry();
        var config = new com.itranswarp.exchange.redis.RedisConfiguration();
        config.setHost("localhost"); config.setPort(6379); config.setPassword("");
        var redis = new com.itranswarp.exchange.redis.RedisService(config) {
            /** 替代唯一会执行的 Redis 命令，不实际连接任何服务。 */
            @Override public Boolean executeScriptReturnBoolean(String sha, String[] keys, String[] values) { return false; }
        };
        var service = new QuotationService();
        ReflectionTestUtils.setField(service, "monitoring", new PipelineMonitoring(registry));
        ReflectionTestUtils.setField(service, "redisService", redis);
        ReflectionTestUtils.setField(service, "zoneId", java.time.ZoneId.of("UTC"));
        try {
            service.processMessage(message(1));
            assertEquals(1L, ReflectionTestUtils.getField(service, "sequenceId"));
            assertNull(registry.find("exchange.pipeline.completed.sequence").gauge());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "quotation", "result", "redis_rejected").counter().count());
        } finally { redis.shutdown(); registry.close(); }
    }

    /** 后续处理失败不能把提前推进的业务序号当成完整成功位置。 */
    @Test void failureDoesNotAdvanceCompletedPosition() {
        var registry = new SimpleMeterRegistry();
        var service = new QuotationService() {
            /** 保留观测外层，隔离数据写入并模拟后续异常。 */
            @Override boolean applyMessage(TickMessage message) {
                if (message.sequenceId == 2) throw new IllegalStateException("隔离行情故障");
                return message.sequenceId != 3;
            }
        };
        ReflectionTestUtils.setField(service, "monitoring", new PipelineMonitoring(registry));
        try {
            service.processMessage(message(1));
            assertThrows(IllegalStateException.class, () -> service.processMessage(message(2)));
            service.processMessage(message(3));
            assertEquals(1, registry.get("exchange.pipeline.completed.sequence").tag("stage", "quotation").gauge().value());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "quotation", "result", "failure").counter().count());
            assertEquals(3, registry.get("exchange.pipeline.duration").tag("stage", "quotation").timer().count());
        } finally { registry.close(); }
    }

    /** 创建不含成交的测试消息，避免真实投影副作用。 */
    private TickMessage message(long sequence) { var message = new TickMessage(); message.sequenceId = sequence; message.ticks = List.of(); return message; }
}
