/* 异步等待观测：覆盖超时后迟到、成功后清理和失败，不需要真实请求或业务事件。 */
package com.itranswarp.exchange.service;

import org.junit.jupiter.api.Test;
import com.itranswarp.exchange.monitoring.PipelineMonitoring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class ApiRequestMonitoringTest {
    /** 同一请求所有终止路径竞争时只统计一次，等待数最终为零。 */
    @Test void lateAndDuplicateResultsAreNotCountedTwice() {
        var registry = new SimpleMeterRegistry();
        var monitor = new ApiRequestMonitoring(new PipelineMonitoring(registry));
        try {
            monitor.started("internal-ref", "create");
            assertEquals(1, registry.get("exchange.api.requests.pending").gauge().value());
            monitor.finished("internal-ref", "timeout");
            monitor.finished("internal-ref", "success");
            monitor.finished("internal-ref", "completed_without_result");
            assertEquals(0, registry.get("exchange.api.requests.pending").gauge().value());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "api_create", "result", "timeout").counter().count());
            assertNull(registry.find("exchange.pipeline.operations").tags("stage", "api_create", "result", "success").counter());
            assertEquals(1, registry.get("exchange.pipeline.duration").tag("stage", "api_create").timer().count());
        } finally { registry.close(); }
    }

    /** 正常结果和发送失败均会清理等待，但不能相互混算。 */
    @Test void successAndSendFailure() {
        var registry = new SimpleMeterRegistry();
        var monitor = new ApiRequestMonitoring(new PipelineMonitoring(registry));
        try {
            monitor.started("a", "cancel"); monitor.finished("a", "success");
            monitor.started("b", "create"); monitor.finished("b", "send_failure");
            assertEquals(0, registry.get("exchange.api.requests.pending").gauge().value());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "api_cancel", "result", "success").counter().count());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "api_create", "result", "send_failure").counter().count());
        } finally { registry.close(); }
    }
}
