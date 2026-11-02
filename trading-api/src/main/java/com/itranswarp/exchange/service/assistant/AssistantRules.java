/* 有限意图和固定解释：模型不能生成余额、执行原因或无依据的投资建议。 */
package com.itranswarp.exchange.service.assistant;

public final class AssistantRules {
  public enum Intent { ACCOUNT, FREEZE, ORDER, DEPOSIT, UNSUPPORTED }
  /** 工具类不实例化。 */
  private AssistantRules(){}
  /** 模型返回的任何额外文本、JSON 或伪造金额一律拒绝。 */
  public static Intent validate(String value){return Intent.valueOf(value);}
  /** 安全拒绝与未知问法分离，不能因为缺少关键词就禁止模型识别普通口语。 */
  public static boolean forbidden(String question){return question.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(别人|他人|其他用户|user\\d|用户[一二三四五六七八九十0-9]+|帮我.*(充值|撤单|下单|买入|卖出)|帮忙.*(充值|撤单|下单)|预测|涨不涨|买什么|忽略.*规则|执行sql).*");}
  /** 本地明确业务意图优先，未知问法交给模型，最终查询仍固定本人身份。 */
  public static Intent classify(String question){
    if(forbidden(question))return Intent.UNSUPPORTED;
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
    default->"当前只支持本人的账户、冻结、订单和入金解释；不执行操作、不预测行情。";};}
}
