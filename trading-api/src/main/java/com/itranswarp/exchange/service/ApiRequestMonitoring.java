/* API 异步请求观测：以内部 refId 清理一次，不将 refId 或用户信息作为指标标签。 */
package com.itranswarp.exchange.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.itranswarp.exchange.monitoring.PipelineMonitoring;

@Component
public class ApiRequestMonitoring {
    private record Sample(String operation, long started) { }
    private final Map<String, Sample> pending = new ConcurrentHashMap<>();
    private final PipelineMonitoring monitoring;

    /** 绑定实际等待样本数量；不读取请求、账户或订单内容。 */
    public ApiRequestMonitoring(PipelineMonitoring monitoring) {
        this.monitoring = monitoring;
        monitoring.gauge("exchange.api.requests.pending", m -> pending.size());
    }

    /** 等待项创建后记录起点，操作名称只由下单、撤单调用方固定传入。 */
    public void started(String refId, String operation) { pending.put(refId, new Sample(operation, System.nanoTime())); }

    /** 正常响应、超时、发送失败和完成清理竞争时，只由首次移除者计数。 */
    public void finished(String refId, String result) {
        Sample sample = pending.remove(refId);
        if (sample != null) {
            monitoring.count("api_" + sample.operation(), result);
            monitoring.duration("api_" + sample.operation(), sample.started());
        }
    }
}
