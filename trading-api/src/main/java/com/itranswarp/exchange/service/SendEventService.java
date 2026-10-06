/* 交易命令发送入口：引擎就绪检查通过后才发送下单、撤单或转账事件。 */
package com.itranswarp.exchange.service;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.messaging.MessageProducer;
import com.itranswarp.exchange.messaging.Messaging;
import com.itranswarp.exchange.messaging.MessagingFactory;

@Component
public class SendEventService {

    @Autowired
    private MessagingFactory messagingFactory;

    private MessageProducer<AbstractEvent> messageProducer;

    @Autowired
    private TradingEngineApiProxyService engineProxy;

    @PostConstruct
    public void init() {
        this.messageProducer = messagingFactory.createMessageProducer(Messaging.Topic.SEQUENCE, AbstractEvent.class);
    }

    /** 统一拦截恢复期间的写入；检查不是跨服务原子事务，发送后的故障仍可能导致原有超时。 */
    public void sendMessage(AbstractEvent message) {
        engineProxy.requireReady();
        this.messageProducer.sendMessage(message);
    }
}
