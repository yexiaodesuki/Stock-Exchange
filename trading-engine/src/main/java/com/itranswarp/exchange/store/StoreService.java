/* 交易事件与历史投影仓储：启动恢复按固定上界分页读取，不修改已有事件。 */
package com.itranswarp.exchange.store;

import java.util.List;
import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.itranswarp.exchange.db.DbTemplate;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.messaging.MessageTypes;
import com.itranswarp.exchange.model.support.EntitySupport;
import com.itranswarp.exchange.model.trade.EventEntity;
import com.itranswarp.exchange.support.LoggerSupport;

@Component
@Transactional
public class StoreService extends LoggerSupport {

    @Autowired
    MessageTypes messageTypes;

    @Autowired
    DbTemplate dbTemplate;

    /** 保留实时缺口补读入口，按升序返回已持久化事件。 */
    public List<AbstractEvent> loadEventsFromDb(long lastEventId) {
        List<EventEntity> events = this.dbTemplate.from(EventEntity.class).where("sequenceId > ?", lastEventId)
                .orderBy("sequenceId").limit(100000).list();
        return decodeEvents(events);
    }

    /** 返回当前已提交历史的固定恢复上界；空库从零开始，不执行充值或数据重置。 */
    public long getLatestEventSequenceId() {
        List<EventEntity> events = dbTemplate.from(EventEntity.class).orderBy("sequenceId DESC").limit(1).list();
        return events.isEmpty() ? 0 : events.get(0).sequenceId;
    }

    /** 按游标读取 (lastEventId, upperBound] 的一页，避免历史增长导致启动永远追不上。 */
    public List<AbstractEvent> loadEventsFromDb(long lastEventId, long upperBound, int pageSize) {
        if (lastEventId < 0 || upperBound < lastEventId || pageSize < 1 || pageSize > 100000) {
            throw new IllegalArgumentException("非法历史分页参数");
        }
        List<EventEntity> events = dbTemplate.from(EventEntity.class)
                .where("sequenceId > ? AND sequenceId <= ?", lastEventId, upperBound)
                .orderBy("sequenceId").limit(pageSize).list();
        return decodeEvents(events);
    }

    /** 对照数据库列验证消息标识，损坏或不一致的历史必须失败，不能悄悄推进恢复位置。 */
    private List<AbstractEvent> decodeEvents(List<EventEntity> rows) {
        List<AbstractEvent> events = new ArrayList<>(rows.size());
        for (EventEntity row : rows) {
            if (!(messageTypes.deserialize(row.data) instanceof AbstractEvent event)
                    || event.sequenceId != row.sequenceId || event.previousId != row.previousId
                    || event.createdAt != row.createdAt) {
                throw new IllegalStateException("历史事件内容与数据库标识不一致，sequenceId=" + row.sequenceId);
            }
            events.add(event);
        }
        return events;
    }

    /** 保存实时历史投影，忽略已存在记录；不用于资产权威状态恢复。 */
    public void insertIgnore(List<? extends EntitySupport> list) {
        dbTemplate.insertIgnore(list);
    }
}
