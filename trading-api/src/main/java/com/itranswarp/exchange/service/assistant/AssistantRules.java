/* 有限意图和固定解释：分离公开行情、项目知识及本人数据，不生成投资建议或执行操作。 */
package com.itranswarp.exchange.service.assistant;

public final class AssistantRules {
  public enum Intent { ACCOUNT, FREEZE, ORDER, DEPOSIT, MARKET, KNOWLEDGE, UNSUPPORTED }
  /** 工具类不实例化。 */
  private AssistantRules(){}
  /** 模型返回的任何额外文本、JSON 或伪造金额一律拒绝。 */
  public static Intent validate(String value){return Intent.valueOf(value);}
  /** 安全拒绝与未知问法分离，不能因为缺少关键词就禁止模型识别普通口语。 */
  public static boolean forbidden(String question){return question.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(别人|他人|其他用户|user\\d|用户[一二三四五六七八九十0-9]+|帮我.*(充值|撤单|下单|买入|卖出)|帮忙.*(充值|撤单|下单)|预测|涨不涨|买什么|忽略.*规则|执行sql).*");}
  /** 规则操作问答先分流；具体本人记录不能被通用知识覆盖。 */
  public static Intent classify(String question){
    if(forbidden(question))return Intent.UNSUPPORTED;
    String lower=question.toLowerCase(java.util.Locale.ROOT);
    boolean personal=question.matches("(?s).*(我的|本人|我.*(订单|委托|入金|充值|资金|余额|持仓)).*");
    if(question.matches("(?s).*(如何|怎么|怎样|哪里|在哪).*(入金|充值|下单|撤单|买入|卖出|注册|登录).*"))return Intent.KNOWLEDGE;
    // 明确的操作说明走知识库；实际请求结果仍由本人数据分支或原页面提供。
    if(!personal && question.matches("(?s).*(半股|价格最小单位|退出登录|会话过期|下单超时|撤单超时|提交超时|充值超时|入金超时|原申请重试|怎么看引用|模型降级|行情时间范围).*"))return Intent.KNOWLEDGE;
    if(question.matches("(?s).*(买入报价|报价).*(高于|低于).*(成交价).*"))return Intent.KNOWLEDGE;
    boolean concept=question.matches("(?s).*(是什么|什么意思|含义|规则|为什么|为何|如何生成|怎么生成|如何计算|怎么计算).*");
    if(lower.matches("(?s).*(行情|走势|成交情况|成交摘要|成交量|成交金额|最近成交|最高价|最低价).*")&&!personal&&!concept)return Intent.MARKET;
    // 兼容第一阶段快捷问法；“限价单为什么冻结”是规则，不查询本人账户。
    if(question.matches("[为什么为何资金被冻结？?\\s]+")&&question.contains("冻结"))return Intent.FREEZE;
    if(!personal && question.matches("(?s).*(如何|怎么(?!样)|是什么|什么意思|含义|规则|为什么|为何|操作|教程|说明|介绍|额度|上限|限制|注册|登录|登陆|部分成交|价差|连接中断|重连).*"))return Intent.KNOWLEDGE;
    if(question.contains("入金")||question.contains("充值"))return Intent.DEPOSIT;
    if(question.contains("冻结"))return Intent.FREEZE;
    if(question.matches("(?s).*(订单|委托|挂单|成交记录|交易记录).*"))return Intent.ORDER;
    if(question.contains("账户")||question.contains("余额")||question.contains("持仓"))return Intent.ACCOUNT;
    return Intent.UNSUPPORTED;
  }
  /** 描述只解释字段含义，不编造历史因果、到账时间或价格预测。 */
  public static String explain(Intent intent){return switch(intent){
    case ACCOUNT->"可用资金可用于新委托；冻结资金用于未完成委托。持仓与资金以本次查询时点为准。";
    case FREEZE->"USD 冻结对应活动买单的限价乘剩余股数；卖单冻结对应证券股数。下方是同一事件位置的数据，不代表历史状态。";
    case ORDER->"活动订单中的 remaining 是剩余股数；历史订单状态及成交明细来自持久查询。没有完整历史盘口，不能判断整段时间未成交的原因。";
    case DEPOSIT->"PENDING 表示尚未确认到账，不能据此判断具体故障或预计时间；SUCCEEDED 表示已确认，REJECTED 表示明确拒绝。请勿换新标识补发待确认申请。";
    case MARKET->"以下只基于本系统模拟成交，统计范围内首末价变化，不是现实报价或日涨跌幅，也不预测未来。";
    case KNOWLEDGE->"以下依据本项目已收录的交易规则和操作文档，不代表现实证券市场全部规则。";
    default->"支持本人的账户、冻结、订单、入金，以及本系统行情摘要和项目规则问答；不执行操作、不预测价格。";};}
}
