/* 模型只识别有限问题类型，不获得账户数据、工具、SQL 或资金操作权限。 */
package com.itranswarp.exchange.service.assistant;

public interface AiModelClient {
  /** 返回白名单意图名称；网络、格式或额度异常由调用方降级，不展示模型原文。 */
  String classify(String question) throws Exception;
  /** 无凭据时保持规则模式，不能伪装成真实模型回答。 */
  boolean configured();
}
