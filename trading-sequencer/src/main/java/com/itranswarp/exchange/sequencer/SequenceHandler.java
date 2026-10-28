/* 定序业务处理：按 Topic 校验命令类型，再由同一事件日志事务完成去重和分配序号。 */
package com.itranswarp.exchange.sequencer;

import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import com.itranswarp.exchange.util.JsonUtil;
import javax.sql.DataSource;

public final class SequenceHandler {
  private final EventJournal journal;
  private final SecuritiesChannels channels;

  /** 复用原定序事务，不改变序号分配、幂等键或持久格式。 */
  public SequenceHandler(DataSource source, SecurityCatalog catalog, String namespace) {
    journal = new EventJournal(source, catalog);
    channels = new SecuritiesChannels(namespace);
  }

  /** 非法来源拒绝；数据库异常继续传播，外层消费服务不能确认 offset。 */
  public SequencedEvent process(String topic, String json) {
    var submission = JsonUtil.readJson(json, Submission.class);
    boolean credit =
        submission.command() instanceof CreditCash
            || submission.command() instanceof CreditPosition;
    if (!topic.equals(channels.topic(credit ? "transfer" : "sequence")))
      throw new IllegalArgumentException("命令不允许从该 Topic 提交");
    return journal.append(submission);
  }
}
