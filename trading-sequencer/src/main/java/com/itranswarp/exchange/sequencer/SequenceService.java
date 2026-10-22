/* 定序生命周期与事务返回观测：成功位置只在事务代理正常返回后推进。 */
package com.itranswarp.exchange.sequencer;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.messaging.MessageConsumer;
import com.itranswarp.exchange.messaging.MessageProducer;
import com.itranswarp.exchange.messaging.MessageTypes;
import com.itranswarp.exchange.messaging.Messaging;
import com.itranswarp.exchange.messaging.MessagingFactory;
import com.itranswarp.exchange.support.LoggerSupport;

/**
 * Sequence events.
 */
@Component
public class SequenceService extends LoggerSupport implements CommonErrorHandler {

    @Autowired
    private com.itranswarp.exchange.monitoring.PipelineMonitoring monitoring = new com.itranswarp.exchange.monitoring.PipelineMonitoring();

    private static final String GROUP_ID = "SequencerGroup";

    @Autowired
    private SequenceHandler sequenceHandler;

    @Autowired
    private MessagingFactory messagingFactory;

    @Autowired
    private MessageTypes messageTypes;

    private MessageProducer<AbstractEvent> messageProducer;

    private AtomicLong sequence;
    private Thread jobThread;
    private volatile boolean running;

    /** 启动原工作线程并绑定可见状态，不因监控额外创建消费者。 */
    @PostConstruct
    public void init() {
        Thread thread = new Thread(() -> {
            logger.info("start sequence job...");
            // TODO: try get global DB lock:
            // while (!hasLock()) { sleep(10000); }
            this.messageProducer = this.messagingFactory.createMessageProducer(Messaging.Topic.TRADE,
                    AbstractEvent.class);

            // find max event id:
            this.sequence = new AtomicLong(this.sequenceHandler.getMaxSequenceId());

            // init consumer:
            logger.info("create message consumer for {}...", getClass().getName());
            // share same group id:
            MessageConsumer consumer = this.messagingFactory.createBatchMessageListener(Messaging.Topic.SEQUENCE,
                    GROUP_ID, this::processMessages, this);
            // start running:
            this.running = true;
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            // close message consumer:
            logger.info("close message consumer for {}...", getClass().getName());
            consumer.stop();
            System.exit(1);
        });
        this.jobThread = thread;
        monitoring.component("sequencer", () -> jobThread != null && jobThread.isAlive() && running && !crash);
        this.jobThread.start();
    }

    @PreDestroy
    public void shutdown() {
        logger.info("shutdown sequence service...");
        running = false;
        if (jobThread != null) {
            jobThread.interrupt();
            try {
                jobThread.join(5000);
            } catch (InterruptedException e) {
                logger.error("interrupt job thread failed", e);
            }
            jobThread = null;
        }
    }

    private volatile boolean crash = false;

    /**
     * Message consumer error handler
     */
    @Override
    public void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
            MessageListenerContainer container, Runnable invokeListener) {
        logger.error("batch error!", thrownException);
        panic();
    }

    private void sendMessages(List<AbstractEvent> messages) {
        this.messageProducer.sendMessages(messages);
    }

    /** 事务调用结束才统计成功；原有致命错误处理与后续发送顺序不变。 */
    private synchronized void processMessages(List<AbstractEvent> messages) {
        if (!running || crash) {
            panic();
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("do sequence for {} messages...", messages.size());
        }
        long start = System.currentTimeMillis();
        List<AbstractEvent> sequenced = null;
        try {
            sequenced = sequenceAndObserve(messages);
        } catch (Throwable e) {
            logger.error("exception when do sequence", e);
            shutdown();
            panic();
            throw new Error(e);
        }

        if (logger.isInfoEnabled()) {
            long end = System.currentTimeMillis();
            logger.info("sequenced {} messages in {} ms. current sequence id: {}", messages.size(), (end - start),
                    this.sequence.get());
        }
        sendMessages(sequenced);
    }

    /** 包含事务提交耗时；提交失败不能推进已成功定序位置，异常交回原处理路径。 */
    List<AbstractEvent> sequenceAndObserve(List<AbstractEvent> messages) throws Exception {
        long started = System.nanoTime();
        try {
            List<AbstractEvent> result = sequenceHandler.sequenceMessages(messageTypes, sequence, messages);
            monitoring.count("sequence", "success", result.size());
            monitoring.progress("sequence", sequence.get());
            return result;
        } catch (Exception | Error e) {
            monitoring.count("sequence", "failure");
            throw e;
        } finally { monitoring.duration("sequence", started); }
    }

    private void panic() {
        this.crash = true;
        this.running = false;
        System.exit(1);
    }
}
