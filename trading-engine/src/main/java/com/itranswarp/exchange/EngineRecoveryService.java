/*
 * 启动时从完整的 MySQL 事件历史恢复引擎，校验成功后才启动实时消费并开放业务。
 * 本阶段不使用快照；要求单引擎运行，事件历史不能被裁剪。
 */
package com.itranswarp.exchange;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.store.StoreService;
import com.itranswarp.exchange.support.LoggerSupport;

@Component
public class EngineRecoveryService extends LoggerSupport implements ApplicationRunner {

    private static final int PAGE_SIZE = 1000;
    private final TradingEngineService engine;
    private final StoreService store;

    /** 注入引擎和事件仓储；构造时不启动消费，允许 Web 查询先看到恢复中状态。 */
    public EngineRecoveryService(TradingEngineService engine, StoreService store) {
        this.engine = engine;
        this.store = store;
    }

    /** Spring 启动完成后执行有限上界重放；失败向启动流程抛出，不开放半恢复状态。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            engine.beginRecovery();
            long target = store.getLatestEventSequenceId();
            logger.info("开始恢复交易引擎，历史上界={}", target);
            while (engine.getLastSequenceId() < target) {
                long previous = engine.getLastSequenceId();
                List<AbstractEvent> page = store.loadEventsFromDb(previous, target, PAGE_SIZE);
                if (page.isEmpty()) {
                    throw new IllegalStateException("历史事件缺失，恢复位置=" + previous + "，目标=" + target);
                }
                engine.replayEvents(page);
                if (engine.getLastSequenceId() <= previous || engine.getLastSequenceId() > target) {
                    throw new IllegalStateException("历史分页没有正确推进恢复位置");
                }
            }
            engine.validate();
            engine.startAfterRecovery();
            logger.info("交易引擎恢复完成，已应用序号={}", engine.getLastSequenceId());
        } catch (Exception e) {
            engine.failRecovery();
            throw new IllegalStateException("交易引擎恢复失败，禁止开放业务", e);
        }
    }
}
