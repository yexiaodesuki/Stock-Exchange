/* 定序观测回归：调用者只在事务代理正常返回之后更新指标，失败进度不能提前推进。 */
package com.itranswarp.exchange.sequencer;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.messaging.MessageTypes;
import com.itranswarp.exchange.monitoring.PipelineMonitoring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class SequenceMonitoringTest {
    /** 真实 Spring 事务代理提交失败，业务方法已返回也不能推进成功指标；不连接数据库。 */
    @Test void transactionFailureDoesNotAdvanceSuccessMetric() throws Exception {
        var registry = new SimpleMeterRegistry();
        var sequence = new AtomicLong(5);
        var service = new SequenceService();
        ReflectionTestUtils.setField(service, "monitoring", new PipelineMonitoring(registry));
        ReflectionTestUtils.setField(service, "sequence", sequence);
        var failCommit = new java.util.concurrent.atomic.AtomicBoolean();
        var handler = new SequenceHandler() {
            /** 模拟事务方法内产生序号，提交由真实代理在方法返回后执行。 */
            @Override public List<AbstractEvent> sequenceMessages(MessageTypes types, AtomicLong position, List<AbstractEvent> messages) {
                position.incrementAndGet();
                return messages;
            }
        };
        var transactions = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            /** 不使用外部事务资源，只测试 Spring 提交调用顺序。 */
            @Override protected Object doGetTransaction() { return new Object(); }
            /** 事务开始不执行 SQL。 */
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            /** 业务方法返回后模拟提交失败。 */
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                if (failCommit.get()) throw new org.springframework.transaction.TransactionSystemException("隔离提交故障");
            }
            /** 没有真实资源需要回滚。 */
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
        var proxy = new org.springframework.aop.framework.ProxyFactory(handler);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(service, "sequenceHandler", proxy.getProxy());
        try {
            service.sequenceAndObserve(List.of());
            failCommit.set(true);
            assertThrows(org.springframework.transaction.TransactionSystemException.class, () -> service.sequenceAndObserve(List.of()));
            assertEquals(6, registry.get("exchange.pipeline.completed.sequence").tag("stage", "sequence").gauge().value());
            assertEquals(1, registry.get("exchange.pipeline.operations").tags("stage", "sequence", "result", "failure").counter().count());
        } finally { registry.close(); }
    }
}
